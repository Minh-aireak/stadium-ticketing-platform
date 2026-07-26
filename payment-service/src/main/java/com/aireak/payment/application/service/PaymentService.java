package com.aireak.payment.application.service;

import com.aireak.payment.application.port.in.GetPaymentUseCase;
import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import com.aireak.payment.application.port.out.PaymentGatewayPort;
import com.aireak.payment.application.port.out.PaymentIdempotencyPort;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.domain.exception.DuplicatePaymentException;
import com.aireak.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Application service: orchestrates payment initiation.
 *
 * <p>Flow:
 * <pre>
 *   1. Create Payment aggregate (INITIATED) + persist — commits immediately (PaymentSagaSteps)
 *   2. Call payment gateway (sync — @CircuitBreaker/@Retry/@Bulkhead on adapter), NO local transaction held
 *   3a. Success → markSucceeded + persist + publish PaymentSucceededEvent — commits immediately
 *   3b. Failure → markFailed + persist + publish PaymentFailedEvent — commits immediately
 * </pre>
 *
 * <p>Not {@code @Transactional} at this level: each persist step is a short, independent
 * REQUIRES_NEW transaction in {@link PaymentSagaSteps}, so the gateway REST call in between
 * never holds a Hikari connection open while waiting on network I/O (that previously let a slow
 * gateway exhaust the connection pool before the gateway's own capacity was actually reached).
 *
 * <p>booking-service listens to PaymentSucceeded/Failed via Kafka
 * and drives the saga to CONFIRMED or CANCELLED accordingly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService implements InitiatePaymentUseCase, GetPaymentUseCase {

    private static final String IDEMPOTENCY_KEY_PREFIX = "payment:idempotency:booking:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(5);

    private final PaymentSagaSteps sagaSteps;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentIdempotencyPort idempotencyPort;

    @Override
    public String execute(InitiatePaymentCommand command) {
        String bookingId = command.bookingId();
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + bookingId;

        // Redis-confirmed duplicate: fast path, no DB write attempted
        if (!idempotencyPort.tryAcquire(idempotencyKey, IDEMPOTENCY_TTL)) {
            return existingPaymentIdOrThrow(bookingId);
        }

        // Step 1: create + persist payment in INITIATED state — commits immediately.
        // tryInsert flushes-and-commits so a unique-constraint violation (the fail-open race
        // window when Redis was down) surfaces here, before any gateway charge is attempted.
        PaymentSagaSteps.InitiateOutcome outcome =
                sagaSteps.tryInitiate(bookingId, command.amount(), command.currency());
        if (outcome instanceof PaymentSagaSteps.InitiateOutcome.AlreadyExists) {
            return existingPaymentIdOrThrow(bookingId);
        }
        String paymentId = ((PaymentSagaSteps.InitiateOutcome.Created) outcome).paymentId();
        log.info("Payment initiated: id={}, bookingId={}", paymentId, bookingId);

        // Step 2 & 3: call gateway (REST call, no local transaction), then commit outcome
        try {
            String gatewayTxId = paymentGatewayPort.charge(
                    bookingId, command.amount(), command.currency());
            sagaSteps.markSucceeded(paymentId, gatewayTxId);
            log.info("Payment succeeded: id={}, gatewayTxId={}", paymentId, gatewayTxId);
        } catch (Exception e) {
            log.error("Payment failed: id={}, reason={}", paymentId, e.getMessage());
            sagaSteps.markFailed(paymentId, e.getMessage());
        }

        return paymentId;
    }

    @Override
    public Optional<Payment> getByBookingId(String bookingId) {
        return paymentRepository.findByBookingId(bookingId);
    }

    private String existingPaymentIdOrThrow(String bookingId) {
        return paymentRepository.findByBookingId(bookingId)
                .map(Payment::getPaymentId)
                .orElseThrow(() -> new DuplicatePaymentException(
                        "Payment for booking " + bookingId + " is already being processed"));
    }
}
