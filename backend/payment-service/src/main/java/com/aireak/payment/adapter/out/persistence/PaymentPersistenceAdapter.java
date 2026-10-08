package com.aireak.payment.adapter.out.persistence;

import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.domain.model.Payment;
import com.aireak.payment.domain.model.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PaymentPersistenceAdapter implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;
    private final PaymentRefundJpaRepository refundJpaRepository;

    // The payment row and the refunds it applied go in together: every caller runs this inside one
    // transaction (PaymentSagaSteps), so a refund cannot be recorded without the refundedAmount it
    // adds to, or the other way round.
    @Override
    public void save(Payment payment) {
        jpaRepository.save(toJpaEntity(payment));
        payment.newRefunds().forEach(refund -> refundJpaRepository.save(PaymentRefundJpaEntity.builder()
                .refundRequestId(refund.refundRequestId())
                .paymentId(payment.getPaymentId())
                .bookingId(payment.getBookingId())
                .amount(refund.amount())
                .currency(payment.getCurrency())
                .gatewayRefundId(refund.gatewayRefundId())
                .reason(refund.reason())
                .createdAt(refund.refundedAt())
                .build()));
    }

    @Override
    public boolean hasRefund(String refundRequestId) {
        return refundJpaRepository.existsById(refundRequestId);
    }

    @Override
    public boolean tryInsert(Payment payment) {
        try {
            jpaRepository.saveAndFlush(toJpaEntity(payment));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    @Override
    public Optional<Payment> findByBookingId(String bookingId) {
        return jpaRepository.findByBookingId(bookingId).map(this::toDomain);
    }

    @Override
    public Optional<Payment> findById(String paymentId) {
        return jpaRepository.findById(paymentId).map(this::toDomain);
    }

    @Override
    public List<Payment> findOpenCardPaymentsCreatedBefore(Instant cutoff, int limit) {
        return jpaRepository.findByStatusAndClientSecretIsNotNullAndCreatedAtBefore(
                        PaymentStatus.INITIATED, cutoff,
                        PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "createdAt")))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private PaymentJpaEntity toJpaEntity(Payment p) {
        return PaymentJpaEntity.builder()
                .paymentId(p.getPaymentId())
                .bookingId(p.getBookingId())
                .customerEmail(p.getCustomerEmail())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .status(p.getStatus())
                .gatewayTransactionId(p.getGatewayTransactionId())
                .failureReason(p.getFailureReason())
                .chargeAttempt(p.getChargeAttempt())
                .gatewayIntentId(p.getGatewayIntentId())
                .clientSecret(p.getClientSecret())
                .refundedAmount(p.getRefundedAmount())
                .version(p.getVersion())
                .build();
    }

    private Payment toDomain(PaymentJpaEntity e) {
        return Payment.reconstitute(
                e.getPaymentId(), e.getBookingId(), e.getCustomerEmail(),
                e.getAmount(), e.getCurrency(), e.getStatus(),
                e.getGatewayTransactionId(), e.getFailureReason(),
                e.getCreatedAt(), e.getChargeAttempt(), e.getVersion(),
                e.getGatewayIntentId(), e.getClientSecret(), e.getRefundedAmount()
        );
    }
}
