package com.aireak.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One applied refund, keyed by booking-service's refund request id (V12). Insert-only: a second
 * insert of the same id is a redelivery that slipped past the {@code hasRefund} check, and the
 * primary key turning it into a failed transaction is exactly right.
 *
 * <p>{@link Persistable} with {@code isNew() == true}, because the id is assigned rather than
 * generated: without it {@code save()} would merge — a SELECT, then an UPDATE of a row that is not
 * there — instead of the plain INSERT this table is for.
 */
@Getter
@Entity
@Table(name = "payment_refunds")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRefundJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "refund_request_id", nullable = false, length = 64)
    private String refundRequestId;

    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "gateway_refund_id", nullable = false, length = 100)
    private String gatewayRefundId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Override
    public String getId() {
        return refundRequestId;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
