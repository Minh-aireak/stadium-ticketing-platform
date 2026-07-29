package com.aireak.payment.adapter.out.persistence;

import com.aireak.payment.application.port.out.PaymentReconciliationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class UnreconciledPaymentPersistenceAdapter implements PaymentReconciliationPort {

    private final UnreconciledPaymentJpaRepository jpaRepository;

    @Override
    public void recordUnpersistedSuccess(String paymentId, String bookingId, String gatewayTransactionId,
                                          BigDecimal amount, String currency, String failureReason) {
        jpaRepository.save(UnreconciledPaymentJpaEntity.of(
                paymentId, bookingId, gatewayTransactionId, amount, currency, failureReason));
    }
}
