package com.aireak.payment.application.service;

import com.aireak.payment.application.port.in.GetPaymentUseCase;
import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.RetryPaymentUseCase;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import com.aireak.payment.application.port.out.PaymentGatewayPort;
import com.aireak.payment.application.port.out.PaymentIdempotencyPort;
import com.aireak.payment.application.port.out.PaymentReconciliationPort;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.domain.exception.DuplicatePaymentException;
import com.aireak.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
 *
 * <p><strong>Charge vs. persist are deliberately separate phases</strong> (step 2 vs. step 3):
 * once {@link PaymentGatewayPort#charge} returns a {@code gatewayTxId}, the customer has
 * actually been charged — a failure persisting that outcome must never fall through to
 * {@code sagaSteps.markFailed}, which would record a successful charge as FAILED while the
 * money was already taken. See {@link #persistSucceededOutcome}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService implements InitiatePaymentUseCase, GetPaymentUseCase, RetryPaymentUseCase {

    private static final String IDEMPOTENCY_KEY_PREFIX = "payment:idempotency:booking:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(5);

    // Short backoff for persisting an already-successful charge — this is retrying our own DB
    // write, not the gateway call, so attempts stay few and fast.
    private static final int PERSIST_MAX_ATTEMPTS = 3;
    private static final Duration PERSIST_RETRY_BACKOFF = Duration.ofMillis(200);

    private final PaymentSagaSteps sagaSteps;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentIdempotencyPort idempotencyPort;
    private final PaymentReconciliationPort reconciliationPort;

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

        // Step 2: charge via the gateway (REST call, no local transaction). A failure here means
        // the charge itself never went through (or was definitively declined) — safe to mark FAILED.
        String gatewayTxId;
        try {
            gatewayTxId = paymentGatewayPort.charge(bookingId, command.amount(), command.currency());
        } catch (Exception e) {
            log.error("Payment charge failed: id={}, reason={}", paymentId, e.getMessage());
            sagaSteps.markFailed(paymentId, e.getMessage());
            return paymentId;
        }

        // Step 3: persist the successful charge outcome — entirely separate from step 2's
        // try/catch (see class javadoc): the customer is already charged at this point.
        persistSucceededOutcome(paymentId, bookingId, gatewayTxId, command.amount(), command.currency());
        return paymentId;
    }

    /**
     * Retries {@code markSucceeded} a few times on failure; if it still can't be persisted, the
     * outcome is handed to {@link PaymentReconciliationPort} instead of being silently lost — the
     * gateway transaction is real and must never be recorded as FAILED (see class javadoc).
     */
    private void persistSucceededOutcome(String paymentId, String bookingId, String gatewayTxId,
                                          BigDecimal amount, String currency) {
        for (int attempt = 1; attempt <= PERSIST_MAX_ATTEMPTS; attempt++) {
            try {
                sagaSteps.markSucceeded(paymentId, gatewayTxId);
                log.info("Payment succeeded: id={}, gatewayTxId={}", paymentId, gatewayTxId);
                return;
            } catch (Exception e) {
                log.error("Persisting successful charge failed (attempt {}/{}): id={}, gatewayTxId={}, reason={}",
                        attempt, PERSIST_MAX_ATTEMPTS, paymentId, gatewayTxId, e.getMessage());
                if (attempt == PERSIST_MAX_ATTEMPTS) {
                    recordForManualReconciliation(paymentId, bookingId, gatewayTxId, amount, currency, e.getMessage());
                    return;
                }
                sleep(PERSIST_RETRY_BACKOFF.multipliedBy(attempt));
            }
        }
    }

    // Last resort once retries are exhausted: this itself must not throw and abandon the outcome
    // with nothing but a log line — if even this durable write fails, that failure is the true
    // last resort and is logged at its own level so it's easy to alert on.
    private void recordForManualReconciliation(String paymentId, String bookingId, String gatewayTxId,
                                                BigDecimal amount, String currency, String reason) {
        try {
            reconciliationPort.recordUnpersistedSuccess(paymentId, bookingId, gatewayTxId, amount, currency, reason);
            log.error("Payment succeeded at the gateway but could not be persisted after {} attempts — " +
                            "recorded for manual reconciliation: id={}, bookingId={}, gatewayTxId={}",
                    PERSIST_MAX_ATTEMPTS, paymentId, bookingId, gatewayTxId);
        } catch (Exception e) {
            log.error("CRITICAL: payment succeeded at the gateway but could not be persisted NOR recorded " +
                            "for reconciliation — id={}, bookingId={}, gatewayTxId={}, amount={} {}: {}",
                    paymentId, bookingId, gatewayTxId, amount, currency, e.getMessage(), e);
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Optional<Payment> getByBookingId(String bookingId) {
        return paymentRepository.findByBookingId(bookingId);
    }

    @Override
    public Optional<Payment> getById(String paymentId) {
        return paymentRepository.findById(paymentId);
    }

    /**
     * Re-opens a FAILED payment (see {@link Payment#retry}) and re-attempts the gateway charge —
     * same two-phase shape as {@link #execute}: reopen-and-persist commits first (so the row is
     * never left "stuck" mid-retry), then the gateway call runs with no local transaction held.
     */
    @Override
    public Optional<String> retry(String paymentId) {
        if (paymentRepository.findById(paymentId).isEmpty()) {
            return Optional.empty();
        }

        Payment payment = sagaSteps.retry(paymentId);
        log.info("Payment retry initiated: id={}, bookingId={}", paymentId, payment.getBookingId());

        String gatewayTxId;
        try {
            gatewayTxId = paymentGatewayPort.charge(payment.getBookingId(), payment.getAmount(), payment.getCurrency());
        } catch (Exception e) {
            log.error("Payment retry charge failed: id={}, reason={}", paymentId, e.getMessage());
            sagaSteps.markFailed(paymentId, e.getMessage());
            return Optional.of(paymentId);
        }

        persistSucceededOutcome(paymentId, payment.getBookingId(), gatewayTxId, payment.getAmount(), payment.getCurrency());
        return Optional.of(paymentId);
    }

    private String existingPaymentIdOrThrow(String bookingId) {
        return paymentRepository.findByBookingId(bookingId)
                .map(Payment::getPaymentId)
                .orElseThrow(() -> new DuplicatePaymentException(
                        "Payment for booking " + bookingId + " is already being processed"));
    }
}
