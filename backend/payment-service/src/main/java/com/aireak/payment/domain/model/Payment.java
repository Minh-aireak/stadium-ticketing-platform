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
    private final List<Object> domainEvents = new ArrayList<>();

    private Payment(String paymentId, String bookingId, BigDecimal amount,
                    String currency, PaymentStatus status, Instant createdAt, Long version) {
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
        this.version = version;
    }

    // ----------------------------------------------------------------
    // Factory
    // ----------------------------------------------------------------

    public static Payment initiate(String bookingId, BigDecimal amount, String currency) {
        String paymentId = UUID.randomUUID().toString();
        Payment payment = new Payment(paymentId, bookingId, amount, currency,
                PaymentStatus.INITIATED, Instant.now(), null);
        payment.domainEvents.add(new PaymentInitiatedEvent(paymentId, bookingId, amount, currency));
        return payment;
    }

    public static Payment reconstitute(String paymentId, String bookingId, BigDecimal amount,
                                        String currency, PaymentStatus status,
                                        String gatewayTransactionId, String failureReason,
                                        Instant createdAt, Long version) {
        Payment p = new Payment(paymentId, bookingId, amount, currency, status, createdAt, version);
        p.gatewayTransactionId = gatewayTransactionId;
        p.failureReason = failureReason;
        return p;
    }

    public static final String GATEWAY_AMBIGUOUS_PREFIX = "GATEWAY_AMBIGUOUS: ";

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
        domainEvents.add(new PaymentSucceededEvent(paymentId, bookingId, amount, currency, gatewayTransactionId));
    }

    public void markFailed(String reason) {
        markFailed(reason, false);
    }

    public void markFailedAmbiguous(String reason) {
        markFailed(reason, true);
    }

    public void markFailed(String reason, boolean ambiguous) {
        requireStatus(PaymentStatus.INITIATED, "markFailed");
        this.status = PaymentStatus.FAILED;
        String fullReason = ambiguous && (reason == null || !reason.startsWith(GATEWAY_AMBIGUOUS_PREFIX))
                ? GATEWAY_AMBIGUOUS_PREFIX + (reason != null ? reason : "")
                : reason;
        this.failureReason = fullReason;
        domainEvents.add(new PaymentFailedEvent(paymentId, bookingId, fullReason));
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
        domainEvents.add(new PaymentRefundedEvent(paymentId, bookingId, amount, currency, gatewayRefundId, reason));
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
        this.status = PaymentStatus.INITIATED;
        this.failureReason = null;
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    public String getPaymentId()              { return paymentId; }
    public String getBookingId()              { return bookingId; }
    public BigDecimal getAmount()             { return amount; }
    public String getCurrency()               { return currency; }
    public PaymentStatus getStatus()          { return status; }
    public String getGatewayTransactionId()   { return gatewayTransactionId; }
    public String getFailureReason()          { return failureReason; }
    public Instant getCreatedAt()             { return createdAt; }
    public Long getVersion()                  { return version; }

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
