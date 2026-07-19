package com.aireak.payment.application.service;

import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import com.aireak.payment.application.port.out.DomainEventPublisher;
import com.aireak.payment.application.port.out.PaymentGatewayPort;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service: orchestrates payment initiation.
 *
 * <p>Flow:
 * <pre>
 *   1. Create Payment aggregate (INITIATED) + persist
 *   2. Call payment gateway (sync — @CircuitBreaker on adapter)
 *   3a. Success → markSucceeded + persist + publish PaymentSucceededEvent
 *   3b. Failure → markFailed + persist + publish PaymentFailedEvent
 * </pre>
 *
 * <p>booking-service listens to PaymentSucceeded/Failed via Kafka
 * and drives the saga to CONFIRMED or CANCELLED accordingly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService implements InitiatePaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final DomainEventPublisher eventPublisher;

    @Override
    @Transactional
    public String execute(InitiatePaymentCommand command) {
        // Step 1: Create and persist payment in INITIATED state
        Payment payment = Payment.initiate(command.bookingId(), command.amount(), command.currency());
        paymentRepository.save(payment);

        String paymentId = payment.getPaymentId();
        log.info("Payment initiated: id={}, bookingId={}", paymentId, command.bookingId());

        // Step 2 & 3: Call gateway, handle result inline
        try {
            String gatewayTxId = paymentGatewayPort.charge(
                    command.bookingId(), command.amount(), command.currency());
            payment.markSucceeded(gatewayTxId);
            log.info("Payment succeeded: id={}, gatewayTxId={}", paymentId, gatewayTxId);
        } catch (Exception e) {
            log.error("Payment failed: id={}, reason={}", paymentId, e.getMessage());
            payment.markFailed(e.getMessage());
        }

        // Always persist final state and publish events
        paymentRepository.save(payment);
        eventPublisher.publishAll(payment.pullDomainEvents());

        return paymentId;
    }
}
