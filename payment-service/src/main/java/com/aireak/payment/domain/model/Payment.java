package com.aireak.payment.domain.model;

import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentInitiatedEvent;
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
 *   <li>Terminal states (SUCCEEDED, FAILED, REFUNDED) are immutable</li>
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
    private final List<Object> domainEvents = new ArrayList<>();

    private Payment(String paymentId, String bookingId, BigDecimal amount,
                    String currency, PaymentStatus status, Instant createdAt) {
        this.paymentId = paymentId;
        this.bookingId = bookingId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    // ----------------------------------------------------------------
    // Factory
    // ----------------------------------------------------------------

    public static Payment initiate(String bookingId, BigDecimal amount, String currency) {
        String paymentId = UUID.randomUUID().toString();
        Payment payment = new Payment(paymentId, bookingId, amount, currency,
                PaymentStatus.INITIATED, Instant.now());
        payment.domainEvents.add(new PaymentInitiatedEvent(paymentId, bookingId, amount, currency));
        return payment;
    }

    public static Payment reconstitute(String paymentId, String bookingId, BigDecimal amount,
                                        String currency, PaymentStatus status,
                                        String gatewayTransactionId, String failureReason,
                                        Instant createdAt) {
        Payment p = new Payment(paymentId, bookingId, amount, currency, status, createdAt);
        p.gatewayTransactionId = gatewayTransactionId;
        p.failureReason = failureReason;
        return p;
    }

    // ----------------------------------------------------------------
    // Domain behavior
    // ----------------------------------------------------------------

    public void markSucceeded(String gatewayTransactionId) {
        requireStatus(PaymentStatus.INITIATED, "markSucceeded");
        this.status = PaymentStatus.SUCCEEDED;
        this.gatewayTransactionId = gatewayTransactionId;
        domainEvents.add(new PaymentSucceededEvent(paymentId, bookingId, amount, currency, gatewayTransactionId));
    }

    public void markFailed(String reason) {
        requireStatus(PaymentStatus.INITIATED, "markFailed");
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        domainEvents.add(new PaymentFailedEvent(paymentId, bookingId, reason));
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
