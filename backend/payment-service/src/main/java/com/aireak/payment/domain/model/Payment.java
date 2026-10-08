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
    // Card mode only (see PaymentService#execute): the PaymentIntent the customer's browser will
    // confirm, created up front so the payment can be found at the gateway by something this side
    // chose -- expiry cancels it, sync retrieves it -- before any webhook has told us its outcome.
    // Null in auto mode, where the charge is created and confirmed in one server-side call and
    // gatewayTransactionId is the first id the gateway ever hands back.
    private String gatewayIntentId;
    // The half of the intent the browser needs to confirm it. Only ever released to the booking's
    // owner (PaymentController#getByBookingId); its presence is what marks a payment as card mode.
    private String clientSecret;
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
    // What has been refunded so far (V12). A payment can be refunded in parts now — one cancelled
    // seat at a time — so "REFUNDED" alone no longer says how much went back.
    private BigDecimal refundedAmount = BigDecimal.ZERO;
    // Refunds applied by this instance and not yet persisted; PaymentPersistenceAdapter#save writes
    // them with the payment row, in the same transaction.
    private final List<RefundRecord> newRefunds = new ArrayList<>();
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
        return reconstitute(paymentId, bookingId, customerEmail, amount, currency, status,
                gatewayTransactionId, failureReason, createdAt, chargeAttempt, version, null, null);
    }

    public static Payment reconstitute(String paymentId, String bookingId, String customerEmail,
                                        BigDecimal amount, String currency, PaymentStatus status,
                                        String gatewayTransactionId, String failureReason,
                                        Instant createdAt, int chargeAttempt, Long version,
                                        String gatewayIntentId, String clientSecret) {
        return reconstitute(paymentId, bookingId, customerEmail, amount, currency, status, gatewayTransactionId,
                failureReason, createdAt, chargeAttempt, version, gatewayIntentId, clientSecret, BigDecimal.ZERO);
    }

    public static Payment reconstitute(String paymentId, String bookingId, String customerEmail,
                                        BigDecimal amount, String currency, PaymentStatus status,
                                        String gatewayTransactionId, String failureReason,
                                        Instant createdAt, int chargeAttempt, Long version,
                                        String gatewayIntentId, String clientSecret, BigDecimal refundedAmount) {
        Payment p = new Payment(paymentId, bookingId, customerEmail, amount, currency, status, createdAt, version);
        p.gatewayTransactionId = gatewayTransactionId;
        p.failureReason = failureReason;
        p.chargeAttempt = chargeAttempt;
        p.gatewayIntentId = gatewayIntentId;
        p.clientSecret = clientSecret;
        p.refundedAmount = refundedAmount == null ? BigDecimal.ZERO : refundedAmount;
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

    /**
     * Card mode: records the PaymentIntent created for the customer to confirm. Only an INITIATED
     * payment can take one, and only once -- a second intent for the same payment would leave
     * the first confirmable at the gateway with nothing here able to find it.
     */
    public void attachIntent(String gatewayIntentId, String clientSecret) {
        requireStatus(PaymentStatus.INITIATED, "attachIntent");
        if (this.gatewayIntentId != null) {
            throw new InvalidPaymentStatusException(
                    "Operation 'attachIntent' already done for payment " + paymentId + ": " + this.gatewayIntentId);
        }
        this.gatewayIntentId = gatewayIntentId;
        this.clientSecret = clientSecret;
    }

    /** True when the customer confirms the charge in the browser rather than this service. */
    public boolean isCardMode() {
        return clientSecret != null;
    }

    /**
     * Card mode: one confirmation attempt was declined, but the intent stays open and the
     * customer can try another card, so this is not a failure of the payment. Keeps the gateway's
     * reason for the status endpoint and moves nothing -- no status change, no event -- because
     * {@link #markFailed} would cancel the booking and release the seats under a customer who is
     * still at the form. What ends a card payment is {@link #markSucceeded}, or {@link #markFailed}
     * from the expiry job once the payment window has closed.
     */
    public void noteAttemptFailure(String reason) {
        requireStatus(PaymentStatus.INITIATED, "noteAttemptFailure");
        String describedReason = (reason == null || reason.isBlank()) ? UNSPECIFIED_REASON : reason;
        this.failureReason = describedReason.length() > MAX_FAILURE_REASON_LENGTH
                ? describedReason.substring(0, MAX_FAILURE_REASON_LENGTH)
                : describedReason;
    }

    public void markSucceeded(String gatewayTransactionId) {
        if (status != PaymentStatus.INITIATED && !isAmbiguousFailure()) {
            throw new InvalidPaymentStatusException(
                    "Operation 'markSucceeded' requires INITIATED or FAILED (ambiguous), current: " + status);
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.gatewayTransactionId = gatewayTransactionId;
        // A card-mode success may follow declined attempts whose reason noteAttemptFailure kept.
        this.failureReason = null;
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

    /** What can still be refunded: the captured amount minus what already went back. */
    public BigDecimal refundableAmount() {
        if (status != PaymentStatus.SUCCEEDED) {
            return BigDecimal.ZERO;
        }
        return amount.subtract(refundedAmount).max(BigDecimal.ZERO);
    }

    /**
     * Records a refund of {@code refundAmount} that the gateway has already issued (see
     * PaymentSagaSteps#markRefunded), so {@code gatewayRefundId} is known by the time this runs.
     *
     * <p>The payment stays SUCCEEDED while part of it is refunded — one cancelled seat of several —
     * and becomes REFUNDED, terminally, once all of it is. Never more than {@link #refundableAmount}:
     * the gateway would refuse it, and recording it would claim money went back that did not.
     */
    public void refund(String refundRequestId, BigDecimal refundAmount, String gatewayRefundId, String reason) {
        requireStatus(PaymentStatus.SUCCEEDED, "refund");
        if (refundAmount.signum() <= 0 || refundAmount.compareTo(refundableAmount()) > 0) {
            throw new InvalidPaymentStatusException("Refund of " + refundAmount + " " + currency + " is outside what "
                    + "payment " + paymentId + " can still refund: " + refundableAmount());
        }
        this.refundedAmount = refundedAmount.add(refundAmount);
        if (refundedAmount.compareTo(amount) >= 0) {
            this.status = PaymentStatus.REFUNDED;
        }
        newRefunds.add(new RefundRecord(refundRequestId, refundAmount, gatewayRefundId, reason, Instant.now()));
        domainEvents.add(new PaymentRefundedEvent(paymentId, bookingId, customerEmail, refundAmount, currency,
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
        if (isCardMode()) {
            // A card payment only reaches FAILED once its window has expired and the booking's
            // seats have gone back on sale; the intent is cancelled at the gateway. There is
            // nothing to re-charge -- the customer starts a new booking.
            throw new InvalidPaymentStatusException(
                    "Operation 'retry' is not available for a card payment: " + paymentId);
        }
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
    public String getGatewayIntentId()        { return gatewayIntentId; }
    public String getClientSecret()           { return clientSecret; }
    public BigDecimal getRefundedAmount()     { return refundedAmount; }

    /** Refunds applied since this instance was loaded, for the repository to persist with it. */
    public List<RefundRecord> newRefunds() {
        return List.copyOf(newRefunds);
    }

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
