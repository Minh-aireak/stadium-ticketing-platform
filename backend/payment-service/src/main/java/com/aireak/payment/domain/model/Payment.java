package com.aireak.payment.domain.model;

import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentInitiatedEvent;
import com.aireak.payment.domain.event.PaymentRefundedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import com.aireak.payment.domain.exception.InvalidPaymentStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root: Payment.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Only INITIATED payments can be marked as succeeded or failed</li>
 *   <li>Only SUCCEEDED payments can be refunded (see {@link #refund})</li>
 *   <li>FAILED and REFUNDED are terminal — immutable except FAILED → INITIATED via {@link #retry}</li>
 * </ul>
 */
public class Payment {

    private final String paymentId;
    private final String bookingId;
    // Recipient of the payment-success receipt email, taken from the caller's validated JWT at
    // initiation. Null when the payment was initiated by an internal-service token, which carries
    // no end-user identity — consumers of PaymentSucceededEvent must tolerate that.
    private final String customerEmail;
    private final BigDecimal amount;
    private final String currency;
    private PaymentStatus status;
    private String gatewayTransactionId; // set on success
    private String failureReason;        // set on failure
    private final Instant createdAt;
    // null only until first persist (initiate()) — Spring Data's isNew() check needs that,
    // so reconstitute() must carry the real value through unchanged after that point. Without
    // this, PaymentPersistenceAdapter would build a fresh JPA entity with version=null on every
    // save, and Spring Data JPA treats any entity with a null @Version as new — markSucceeded()/
    // markFailed() would then try to INSERT a row whose id already exists instead of updating it.
    private final Long version;
    // Which gateway attempt this payment is on, and therefore which idempotency key its next
    // charge presents. See #chargeIdempotencyKey and #retry.
    private int chargeAttempt;
    private final List<Object> domainEvents = new ArrayList<>();

    private Payment(String paymentId, String bookingId, String customerEmail, BigDecimal amount,
                    String currency, PaymentStatus status, Instant createdAt, Long version) {
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.customerEmail = customerEmail;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
        this.version = version;
    }

    // ----------------------------------------------------------------
    // Factory
    // ----------------------------------------------------------------

    public static Payment initiate(String bookingId, String customerEmail, BigDecimal amount, String currency) {
        String paymentId = UUID.randomUUID().toString();
        Payment payment = new Payment(paymentId, bookingId, customerEmail, amount, currency,
                PaymentStatus.INITIATED, Instant.now(), null);
        payment.domainEvents.add(new PaymentInitiatedEvent(paymentId, bookingId, amount, currency));
        return payment;
    }

    public static Payment reconstitute(String paymentId, String bookingId, String customerEmail,
                                        BigDecimal amount, String currency, PaymentStatus status,
                                        String gatewayTransactionId, String failureReason,
                                        Instant createdAt, int chargeAttempt, Long version) {
        Payment p = new Payment(paymentId, bookingId, customerEmail, amount, currency, status, createdAt, version);
        p.gatewayTransactionId = gatewayTransactionId;
        p.failureReason = failureReason;
        p.chargeAttempt = chargeAttempt;
        return p;
    }

    public static final String GATEWAY_AMBIGUOUS_PREFIX = "GATEWAY_AMBIGUOUS: ";

    /**
     * Stands in for a failure reason the gateway did not give, so that {@link #failureReason} and
     * the {@link PaymentFailedEvent} it raises are never null. See {@link #markFailed(String,
     * boolean)} for what a null one used to cost.
     */
    static final String UNSPECIFIED_REASON = "No reason reported by the payment gateway";

    /**
     * Width of {@code payments.failure_reason} (see {@code PaymentJpaEntity}). The reason is
     * whatever the gateway said, and since chargeFallback stopped substituting its own wording for
     * a decline that is a third-party string on the hot path. One longer than the column would
     * fail the markFailed transaction at commit, leaving the payment INITIATED with no
     * PaymentFailedEvent and its booking stuck in PENDING_PAYMENT until BookingReconciliationJob
     * came round. Trimming from the end keeps {@link #GATEWAY_AMBIGUOUS_PREFIX} intact, which
     * {@link #isAmbiguousFailure} reads to decide whether a retry may use a fresh Stripe key.
     */
    private static final int MAX_FAILURE_REASON_LENGTH = 500;

    // ----------------------------------------------------------------
    // Domain behavior
    // ----------------------------------------------------------------

    public void markSucceeded(String gatewayTransactionId) {
        if (status != PaymentStatus.INITIATED && !isAmbiguousFailure()) {
            throw new InvalidPaymentStatusException(
                    "Operation 'markSucceeded' requires INITIATED or FAILED (ambiguous), current: " + status);
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.gatewayTransactionId = gatewayTransactionId;
        domainEvents.add(new PaymentSucceededEvent(paymentId, bookingId, customerEmail, amount, currency,
                gatewayTransactionId));
    }

    public void markFailed(String reason) {
        markFailed(reason, false);
    }

    public void markFailedAmbiguous(String reason) {
        markFailed(reason, true);
    }

    /**
     * The reason is substituted rather than passed through when it is missing, because it does not
     * stay here. It rides {@link PaymentFailedEvent} to booking-service, becomes the booking's
     * cancellation reason, and is rendered into the customer's cancellation email as
     * {@code ${reason}} — and FreeMarker throws on a null interpolation. {@code
     * TransactionalEmailService} catches that and sends nothing, which also means no in-app
     * notification record is written, so a payment failure the gateway did not explain would
     * cancel a booking and never tell the customer it had.
     *
     * <p>Null is not a hypothetical here. Stripe's PaymentIntent carries no {@code
     * last_payment_error} for some declines, and {@code StripeWebhookController#toCommand} passes
     * null when it is absent; the other callers pass {@code e.getMessage()}, which is null for a
     * NullPointerException and plenty besides.
     */
    public void markFailed(String reason, boolean ambiguous) {
        requireStatus(PaymentStatus.INITIATED, "markFailed");
        this.status = PaymentStatus.FAILED;
        String describedReason = (reason == null || reason.isBlank()) ? UNSPECIFIED_REASON : reason;
        String fullReason = ambiguous && !describedReason.startsWith(GATEWAY_AMBIGUOUS_PREFIX)
                ? GATEWAY_AMBIGUOUS_PREFIX + describedReason
                : describedReason;
        this.failureReason = fullReason.length() > MAX_FAILURE_REASON_LENGTH
                ? fullReason.substring(0, MAX_FAILURE_REASON_LENGTH)
                : fullReason;
        domainEvents.add(new PaymentFailedEvent(paymentId, bookingId, this.failureReason));
    }

    /**
     * Refunds a SUCCEEDED payment (e.g. its booking's match was cancelled) — the gateway call
     * itself happens before this (see PaymentSagaSteps#markRefunded), so {@code gatewayRefundId}
     * is already known by the time this runs. Terminal: a refund can never be retried or reversed
     * through this aggregate.
     */
    public void refund(String gatewayRefundId, String reason) {
        requireStatus(PaymentStatus.SUCCEEDED, "refund");
        this.status = PaymentStatus.REFUNDED;
        domainEvents.add(new PaymentRefundedEvent(paymentId, bookingId, customerEmail, amount, currency,
                gatewayRefundId, reason));
    }

    public boolean isAmbiguousFailure() {
        return status == PaymentStatus.FAILED && failureReason != null && failureReason.startsWith(GATEWAY_AMBIGUOUS_PREFIX);
    }

    /**
     * Re-opens a FAILED payment for another gateway attempt, on the same row — bookingId has a
     * unique constraint (see {@code PaymentJpaEntity}), so a fresh {@link #initiate} for the same
     * booking would just violate it. Resets back to {@link PaymentStatus#INITIATED}: the domain
     * has no separate "PENDING" status, and INITIATED already means exactly that ("payment
     * request sent to gateway" — see {@link PaymentStatus}), the same state a brand-new payment
     * starts in.
     */
    public void retry() {
        requireStatus(PaymentStatus.FAILED, "retry");
        // Read before failureReason is cleared, since that string is what carries the distinction.
        boolean ambiguous = isAmbiguousFailure();
        this.status = PaymentStatus.INITIATED;
        this.failureReason = null;
        if (!ambiguous) {
            this.chargeAttempt++;
        }
    }

    /**
     * The idempotency key the next gateway charge must present.
     *
     * <p>Stripe saves the response of the first request made with a given key and replays it for
     * every later request presenting that key, for 24 hours, whether that response was a success
     * or an error. So the key decides what a retry is even capable of doing, and the two kinds of
     * failure need opposite answers:
     *
     * <ul>
     *   <li><b>A definitive decline</b> provably moved no money. Retrying under the same key gets
     *       the decline replayed and nothing else, which is what {@link #retry} used to do: the
     *       key was the bare bookingId on every attempt, so
     *       {@code POST /api/v1/payments/{id}/retry} could not succeed for a full day after the
     *       first failure. {@link #retry} bumps the attempt, and this returns a key Stripe has
     *       not seen.</li>
     *   <li><b>An ambiguous failure</b> may have charged the customer already — the request
     *       reached Stripe and the response was lost. There the old key is the point: replaying
     *       it returns whatever really happened, so a lost success is recovered instead of made a
     *       second time. {@link #retry} leaves the attempt alone, and this returns the same key.
     *       Handing that case a fresh key would turn "retry never works" into "retry can charge
     *       twice", which is the worse of the two by a distance.</li>
     * </ul>
     *
     * <p>Attempt 0 is the bare bookingId rather than a suffixed form, so the key a payment already
     * in flight was charged under does not change underneath it on deploy.
     */
    public String chargeIdempotencyKey() {
        return chargeAttempt == 0 ? bookingId : bookingId + ":retry:" + chargeAttempt;
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    public String getPaymentId()              { return paymentId; }
    public String getBookingId()              { return bookingId; }
    public String getCustomerEmail()          { return customerEmail; }
    public BigDecimal getAmount()             { return amount; }
    public String getCurrency()               { return currency; }
    public PaymentStatus getStatus()          { return status; }
    public String getGatewayTransactionId()   { return gatewayTransactionId; }
    public String getFailureReason()          { return failureReason; }
    public Instant getCreatedAt()             { return createdAt; }
    public Long getVersion()                  { return version; }
    public int getChargeAttempt()             { return chargeAttempt; }

    public List<Object> pullDomainEvents() {
        List<Object> events = Collections.unmodifiableList(new ArrayList<>(domainEvents));
        domainEvents.clear();
        return events;
    }

    private void requireStatus(PaymentStatus expected, String op) {
        if (status != expected) {
            throw new InvalidPaymentStatusException(
                    "Operation '" + op + "' requires " + expected + ", current: " + status);
        }
    }
}
