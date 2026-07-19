package com.aireak.payment.adapter.out.persistence;

import com.aireak.common.persistence.BaseAuditEntity;
import com.aireak.payment.domain.model.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "payments",
        indexes = @Index(name = "idx_payments_booking_id", columnList = "booking_id"))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentJpaEntity extends BaseAuditEntity {

    @Id
    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "gateway_transaction_id", length = 100)
    private String gatewayTransactionId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version")
    private Long version;
}
