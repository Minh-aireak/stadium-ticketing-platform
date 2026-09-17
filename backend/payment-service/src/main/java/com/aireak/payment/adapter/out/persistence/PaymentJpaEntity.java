package com.aireak.payment.adapter.out.persistence;

import com.aireak.common.persistence.BaseAuditEntity;
import com.aireak.payment.domain.model.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uq_payments_booking_id", columnNames = "booking_id"))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentJpaEntity extends BaseAuditEntity {

    @Id
    @Column(name = "payment_id", nullable = false, length = 36)
    private String paymentId;

    @Column(name = "booking_id", nullable = false, length = 36)
    private String bookingId;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "gateway_transaction_id", length = 100)
    private String gatewayTransactionId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    // Which gateway attempt the next charge is, so a retry presents an idempotency key Stripe has
    // not already answered. See Payment#chargeIdempotencyKey.
    @Column(name = "charge_attempt", nullable = false)
    private int chargeAttempt;

    // Card mode only: the PaymentIntent the customer's browser confirms, and the secret it needs
    // to do so. See Payment#attachIntent and V11__add_intent_to_payments.sql.
    @Column(name = "gateway_intent_id", length = 100)
    private String gatewayIntentId;

    @Column(name = "client_secret", length = 200)
    private String clientSecret;

    @Version
    @Column(name = "version")
    private Long version;
}
