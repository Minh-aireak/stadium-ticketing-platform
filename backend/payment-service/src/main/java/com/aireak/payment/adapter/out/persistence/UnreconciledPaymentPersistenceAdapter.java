package com.aireak.payment.adapter.out.persistence;

import com.aireak.payment.application.port.out.PaymentReconciliationPort;
import com.aireak.payment.domain.model.UnreconciledPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class UnreconciledPaymentPersistenceAdapter implements PaymentReconciliationPort {

    private final UnreconciledPaymentJpaRepository jpaRepository;

    @Override
    public void recordUnpersistedSuccess(String paymentId, String bookingId, String gatewayTransactionId,
                                          BigDecimal amount, String currency, String failureReason) {
        jpaRepository.save(UnreconciledPaymentJpaEntity.charge(
                paymentId, bookingId, gatewayTransactionId, amount, currency, failureReason));
    }

    @Override
    public void recordUnpersistedRefund(String paymentId, String bookingId, String gatewayRefundId,
                                         BigDecimal amount, String currency, String failureReason) {
        jpaRepository.save(UnreconciledPaymentJpaEntity.refund(
                paymentId, bookingId, gatewayRefundId, amount, currency, failureReason));
    }

    @Override
    public List<UnreconciledPayment> findUnresolvedOlderThan(Instant cutoff, int limit) {
        return jpaRepository.findByResolvedFalseAndCreatedAtBefore(cutoff, PageRequest.of(0, limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private UnreconciledPayment toDomain(UnreconciledPaymentJpaEntity entity) {
        return new UnreconciledPayment(
                entity.getId(),
                entity.getPaymentId(),
                entity.getBookingId(),
                entity.getGatewayTransactionId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getFailureReason(),
                entity.isResolved(),
                entity.getKind(),
                entity.getCreatedAt()
        );
    }
}

