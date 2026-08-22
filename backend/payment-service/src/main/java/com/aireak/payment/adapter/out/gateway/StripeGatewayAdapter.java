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
    @CircuitBreaker(name = "payment-gateway", fallbackMethod = "chargeFallback")
    @Retry(name = "payment-gateway")
    public String charge(String bookingId, BigDecimal amount, String currency) {
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

            RequestOptions requestOptions = RequestOptions.builder()
                    .setIdempotencyKey(bookingId)
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
    @CircuitBreaker(name = "payment-gateway", fallbackMethod = "refundFallback")
    @Retry(name = "payment-gateway")
    public String refund(String gatewayTransactionId, BigDecimal amount, String currency) {
        try {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(gatewayTransactionId)
                    .setAmount(toSmallestUnit(amount, currency))
                    .build();

            // Idempotency key derived from the charge being refunded — a retried refund request
            // for the same PaymentIntent must never double-refund it.
            RequestOptions requestOptions = RequestOptions.builder()
                    .setIdempotencyKey("refund:" + gatewayTransactionId)
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

    private String chargeFallback(String bookingId, BigDecimal amount, String currency, Throwable t) {
        log.error("Payment gateway unavailable for bookingId={}: {}", bookingId, t.getMessage());
        throw new RuntimeException("Payment gateway unavailable", t);
    }

    private String refundFallback(String gatewayTransactionId, BigDecimal amount, String currency, Throwable t) {
        log.error("Payment gateway unavailable for refund of paymentIntentId={}: {}",
                gatewayTransactionId, t.getMessage());
        throw new RuntimeException("Payment gateway unavailable", t);
    }
}
