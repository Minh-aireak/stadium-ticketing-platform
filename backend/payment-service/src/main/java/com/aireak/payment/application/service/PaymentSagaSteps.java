package com.aireak.payment.application.service;

import com.aireak.payment.application.port.out.DomainEventPublisher;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// REQUIRES_NEW so each step commits independently of the gateway call in between
// (mirrors BookingSagaSteps in booking-service). Own bean because @Transactional is
// proxy-based — self-invocation from PaymentService would silently skip it.
@Component
@RequiredArgsConstructor
class PaymentSagaSteps {

    private final PaymentRepository paymentRepository;
    private final DomainEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InitiateOutcome tryInitiate(String bookingId, BigDecimal amount, String currency) {
        Payment payment = Payment.initiate(bookingId, amount, currency);
        if (!paymentRepository.tryInsert(payment)) {
            return new InitiateOutcome.AlreadyExists();
        }
        return new InitiateOutcome.Created(payment.getPaymentId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSucceeded(String paymentId, String gatewayTransactionId) {
        Payment payment = findOrThrow(paymentId);
        payment.markSucceeded(gatewayTransactionId);
        saveAndPublish(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String paymentId, String reason) {
        Payment payment = findOrThrow(paymentId);
        payment.markFailed(reason);
        saveAndPublish(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedAmbiguous(String paymentId, String reason) {
        Payment payment = findOrThrow(paymentId);
        payment.markFailedAmbiguous(reason);
        saveAndPublish(payment);
    }

    // Returns the reopened Payment so the caller (PaymentService) can read bookingId/amount/
    // currency for the follow-up gateway charge without a second lookup.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment retry(String paymentId) {
        Payment payment = findOrThrow(paymentId);
        payment.retry();
        paymentRepository.save(payment);
        return payment;
    }

    private void saveAndPublish(Payment payment) {
        paymentRepository.save(payment);
        eventPublisher.publishAll(payment.pullDomainEvents());
    }

    private Payment findOrThrow(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));
    }

    // Outcome of {@link #tryInitiate}: distinguishes a payment this request just created from one
    // that already existed (idempotency race / Redis-guard miss), so the caller knows whether to
    // proceed to the gateway call or short-circuit to the existing payment's result.
    sealed interface InitiateOutcome {
        record Created(String paymentId) implements InitiateOutcome {}
        record AlreadyExists() implements InitiateOutcome {}
    }
}
