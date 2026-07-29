package com.aireak.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment whose gateway charge succeeded but whose SUCCEEDED outcome could not be persisted
 * to {@code payments} even after retrying — see {@code PaymentService#persistSucceededOutcome}.
 * Awaits manual reconciliation; never written to by application code once created.
 */
@Getter
@Entity
@Table(name = "unreconciled_payments")
@NoArgsConstructor
@AllArgsConstructor
public class UnreconciledPaymentJpaEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "gateway_transaction_id", nullable = false)
    private String gatewayTransactionId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static UnreconciledPaymentJpaEntity of(String paymentId, String bookingId,
            String gatewayTransactionId, BigDecimal amount, String currency, String failureReason) {
        return new UnreconciledPaymentJpaEntity(
                null, paymentId, bookingId, gatewayTransactionId, amount, currency, failureReason,
                false, Instant.now());
    }
}
