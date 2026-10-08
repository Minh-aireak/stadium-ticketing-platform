package com.aireak.payment.adapter.out.gateway;

import com.aireak.payment.application.port.out.PaymentDeclinedException;
import com.aireak.payment.application.port.out.PaymentGatewayPort;
import com.stripe.Stripe;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Stripe payment gateway adapter (test mode).
 *
 * <p>@Bulkhead + @CircuitBreaker + @Retry applied here — hexagonal rule: only adapter layer
 * uses Resilience4j annotations.
 *
 * <p>The fallbacks hang off {@code @Retry}, not {@code @CircuitBreaker}: a fallback is applied
 * from OUTSIDE the aspect it is declared on and fires on any {@code Throwable}, so declared on the
 * breaker it replaced the exception on the very first failure — before the Retry aspect (which
 * sits outside the CircuitBreaker aspect) could match it against {@code ignoreExceptions}. That is
 * what made {@code retry.instances.payment-gateway.ignoreExceptions} dead config and charged every
 * declined card a second time. See TicketInventoryRestAdapter for the same note on the other side.
 */
@Slf4j
@Component
public class StripeGatewayAdapter implements PaymentGatewayPort {

    // Currencies Stripe treats as having no fractional/decimal minor unit.
    private static final Set<String> ZERO_DECIMAL_CURRENCIES = Set.of(
            "VND", "JPY", "KRW", "CLP", "VUV", "XOF", "XAF", "BIF", "DJF", "GNF",
            "KMF", "PYG", "RWF", "UGX", "XPF"
    );

    @Value("${stripe.secret-key}")
    private String secretKey;

    @Value("${stripe.test-payment-method:pm_card_visa}")
    private String testPaymentMethod;

    // SDK defaults (30s connect / 80s read) are far too long to hold open inside a synchronous
    // request thread — shortened so a bad network fails fast into Retry/CircuitBreaker instead
    // of blocking a Tomcat thread + Bulkhead permit for up to 110s per attempt.
    @Value("${stripe.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${stripe.read-timeout-ms:10000}")
    private int readTimeoutMs;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
        Stripe.setConnectTimeout(connectTimeoutMs);
        Stripe.setReadTimeout(readTimeoutMs);
    }

    @Override
    @Bulkhead(name = "payment-gateway", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "payment-gateway")
    @Retry(name = "payment-gateway", fallbackMethod = "chargeFallback")
    public String charge(String idempotencyKey, String bookingId, BigDecimal amount, String currency) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(toSmallestUnit(amount, currency))
                    .setCurrency(currency.toLowerCase())
                    .addPaymentMethodType("card")
                    .setPaymentMethod(testPaymentMethod)
                    .setConfirm(true)
                    .setOffSession(true)
                    .putMetadata("bookingId", bookingId)
                    .build();

            // Supplied by the caller, not derived from bookingId here: Stripe replays the stored
            // response for a repeated key for 24 hours, so this value is what decides whether a
            // retry re-attempts the charge or is handed the previous attempt's answer. Both are
            // wanted, on different failures -- see PaymentGatewayPort#charge.
            RequestOptions requestOptions = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();

            PaymentIntent intent = PaymentIntent.create(params, requestOptions);

            if (!"succeeded".equals(intent.getStatus())) {
                throw new PaymentDeclinedException(
                        "Stripe PaymentIntent did not succeed: bookingId=" + bookingId
                                + ", status=" + intent.getStatus());
            }

            log.info("[STRIPE] Charged: bookingId={}, amount={} {}, paymentIntentId={}",
                    bookingId, amount, currency, intent.getId());
            return intent.getId();
        } catch (CardException | InvalidRequestException e) {
            // Business-level decline, not a gateway fault — see PaymentDeclinedException.
            throw new PaymentDeclinedException(
                    "Payment declined for bookingId=" + bookingId + ": " + e.getMessage(), e);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe charge failed for bookingId=" + bookingId, e);
        }
    }

    @Override
    @Bulkhead(name = "payment-gateway", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "payment-gateway")
    @Retry(name = "payment-gateway", fallbackMethod = "refundFallback")
    public String refund(String idempotencyKey, String gatewayTransactionId, BigDecimal amount, String currency) {
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(gatewayTransactionId)
                    .setAmount(toSmallestUnit(amount, currency))
                    .build();

            // One key per refund request (see PaymentGatewayPort#refund): a retried request must
            // never double-refund, and a second partial refund of the same PaymentIntent must not
            // be answered with the first one's reply.
            RequestOptions requestOptions = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();

            Refund refund = Refund.create(params, requestOptions);

            log.info("[STRIPE] Refunded: paymentIntentId={}, amount={} {}, refundId={}",
                    gatewayTransactionId, amount, currency, refund.getId());
            return refund.getId();
        } catch (InvalidRequestException e) {
            throw new PaymentDeclinedException(
                    "Refund rejected for paymentIntentId=" + gatewayTransactionId + ": " + e.getMessage(), e);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe refund failed for paymentIntentId=" + gatewayTransactionId, e);
        }
    }

    /**
     * Card mode: the intent is created with {@code automatic_payment_methods} (redirects off) and
     * no payment method, so it sits at {@code requires_payment_method} until the browser confirms it. The
     * bookingId metadata is what {@code StripeWebhookController} uses to find the payment again.
     * {@code @Retry} is safe here for the same reason it is on {@link #charge}: the idempotency key
     * makes a repeated create return the intent the first attempt made.
     */
    @Override
    @Bulkhead(name = "payment-gateway", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "payment-gateway")
    @Retry(name = "payment-gateway", fallbackMethod = "createIntentFallback")
    public IntentHandle createIntent(String idempotencyKey, String bookingId, BigDecimal amount, String currency) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(toSmallestUnit(amount, currency))
                    .setCurrency(currency.toLowerCase())
                    // allow_redirects=never keeps the Payment Element to methods that complete on
                    // the page (cards, wallets). A redirect-based method would send the customer
                    // off the storefront mid-countdown and, from a server-side confirm, make Stripe
                    // demand a return_url before it will do anything at all.
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                            .build())
                    .putMetadata("bookingId", bookingId)
                    .build();

            RequestOptions requestOptions = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey)
                    .build();

            PaymentIntent intent = PaymentIntent.create(params, requestOptions);
            log.info("[STRIPE] Intent opened: bookingId={}, amount={} {}, paymentIntentId={}, status={}",
                    bookingId, amount, currency, intent.getId(), intent.getStatus());
            return new IntentHandle(intent.getId(), intent.getClientSecret());
        } catch (InvalidRequestException e) {
            throw new PaymentDeclinedException(
                    "Payment intent rejected for bookingId=" + bookingId + ": " + e.getMessage(), e);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe intent creation failed for bookingId=" + bookingId, e);
        }
    }

    @Override
    @Bulkhead(name = "payment-gateway", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "payment-gateway")
    @Retry(name = "payment-gateway", fallbackMethod = "intentLookupFallback")
    public IntentSnapshot retrieveIntent(String gatewayIntentId) {
        try {
            return snapshotOf(PaymentIntent.retrieve(gatewayIntentId));
        } catch (StripeException e) {
            throw new RuntimeException("Stripe intent lookup failed for paymentIntentId=" + gatewayIntentId, e);
        }
    }

    @Override
    @Bulkhead(name = "payment-gateway", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "payment-gateway")
    @Retry(name = "payment-gateway", fallbackMethod = "intentLookupFallback")
    public IntentSnapshot cancelIntent(String gatewayIntentId) {
        try {
            PaymentIntent intent = PaymentIntent.retrieve(gatewayIntentId);
            // Only an open intent can be cancelled. Anything else is already an answer, and
            // asking Stripe to cancel a succeeded intent is an InvalidRequestException that would
            // read as a failure of this call rather than as the success it is.
            IntentSnapshot before = snapshotOf(intent);
            if (before.outcome() != IntentOutcome.OPEN) {
                return before;
            }
            PaymentIntent cancelled = intent.cancel(PaymentIntentCancelParams.builder()
                    .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                    .build());
            log.info("[STRIPE] Intent cancelled: paymentIntentId={}, status={}", gatewayIntentId, cancelled.getStatus());
            return snapshotOf(cancelled);
        } catch (InvalidRequestException e) {
            // Raced by the customer confirming between the retrieve and the cancel. Read it again
            // rather than guess: whatever Stripe says now is the outcome to record.
            log.info("[STRIPE] Cancel refused for paymentIntentId={} ({}); re-reading", gatewayIntentId, e.getMessage());
            return retrieveIntent(gatewayIntentId);
        } catch (StripeException e) {
            throw new RuntimeException("Stripe intent cancel failed for paymentIntentId=" + gatewayIntentId, e);
        }
    }

    /**
     * Stripe's PaymentIntent statuses, folded to what the payment cares about. {@code
     * requires_payment_method} after a decline still carries {@code last_payment_error}, which is
     * the reason the status endpoint shows the customer while the form stays open.
     */
    private static IntentSnapshot snapshotOf(PaymentIntent intent) {
        String failureMessage = intent.getLastPaymentError() == null ? null : intent.getLastPaymentError().getMessage();
        IntentOutcome outcome = switch (intent.getStatus()) {
            case "succeeded" -> IntentOutcome.SUCCEEDED;
            case "canceled" -> IntentOutcome.CANCELED;
            case "processing" -> IntentOutcome.PROCESSING;
            // requires_payment_method, requires_confirmation, requires_action, requires_capture
            default -> IntentOutcome.OPEN;
        };
        return new IntentSnapshot(outcome, failureMessage);
    }

    /**
     * Stripe amounts are expressed in the currency's smallest unit. Zero-decimal currencies
     * (e.g. VND) have no fractional unit, so the amount is used as-is; all others are multiplied
     * by 100 (e.g. USD dollars -> cents).
     */
    private long toSmallestUnit(BigDecimal amount, String currency) {
        if (ZERO_DECIMAL_CURRENCIES.contains(currency.toUpperCase())) {
            return amount.longValueExact();
        }
        return amount.multiply(BigDecimal.valueOf(100)).longValueExact();
    }

    private String chargeFallback(String idempotencyKey, String bookingId, BigDecimal amount,
                                  String currency, Throwable t) {
        // A decline travels unchanged. PaymentService already reads the cause chain to tell a
        // decline from an ambiguous failure, but it records e.getMessage() as the payment's
        // failureReason -- and that reason does not stay here: it rides PaymentFailedEvent to
        // booking-service, becomes the booking's cancellation reason, and is rendered into the
        // customer's cancellation email. Replacing it told a customer whose card was declined
        // that the payment gateway was unavailable.
        if (t instanceof PaymentDeclinedException declined) {
            throw declined;
        }
        log.error("Payment gateway unavailable for bookingId={}: {}", bookingId, t.getMessage());
        throw new RuntimeException("Payment gateway unavailable", t);
    }

    private IntentHandle createIntentFallback(String idempotencyKey, String bookingId, BigDecimal amount,
                                              String currency, Throwable t) {
        if (t instanceof PaymentDeclinedException declined) {
            throw declined;
        }
        log.error("Payment gateway unavailable for bookingId={}: {}", bookingId, t.getMessage());
        throw new RuntimeException("Payment gateway unavailable", t);
    }

    private IntentSnapshot intentLookupFallback(String gatewayIntentId, Throwable t) {
        log.error("Payment gateway unavailable for paymentIntentId={}: {}", gatewayIntentId, t.getMessage());
        throw new RuntimeException("Payment gateway unavailable", t);
    }

    private String refundFallback(String idempotencyKey, String gatewayTransactionId, BigDecimal amount,
                                  String currency, Throwable t) {
        // Same reasoning as chargeFallback: a refund Stripe actively rejected is not the gateway
        // being unreachable, and whoever reads the dead-lettered refund needs to know which it was.
        if (t instanceof PaymentDeclinedException declined) {
            throw declined;
        }
        log.error("Payment gateway unavailable for refund of paymentIntentId={}: {}",
                gatewayTransactionId, t.getMessage());
        throw new RuntimeException("Payment gateway unavailable", t);
    }
}
