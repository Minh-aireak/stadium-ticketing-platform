package com.aireak.payment.application.service;

import com.aireak.payment.application.port.out.PaymentDeclinedException;
import com.aireak.payment.application.port.in.ExpireCardPaymentsUseCase;
import com.aireak.payment.application.port.in.GetPaymentUseCase;
import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.RefundCommand;
import com.aireak.payment.application.port.in.RefundPaymentUseCase;
import com.aireak.payment.application.port.in.RetryPaymentUseCase;
import com.aireak.payment.application.port.in.SyncPaymentUseCase;
import com.aireak.payment.application.port.in.command.InitiatePaymentCommand;
import com.aireak.payment.config.PaymentModeProperties;
import com.aireak.payment.domain.model.PaymentStatus;
import com.aireak.payment.application.port.out.PaymentGatewayPort;
import com.aireak.payment.application.port.out.PaymentIdempotencyPort;
import com.aireak.payment.application.port.out.PaymentIdempotencyResult;
import com.aireak.payment.application.port.out.PaymentReconciliationPort;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.domain.exception.DuplicatePaymentException;
import com.aireak.payment.domain.exception.InvalidPaymentStatusException;
import com.aireak.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application service: orchestrates payment initiation.
 *
 * <p>Flow:
 * <pre>
 *   1. Create Payment aggregate (INITIATED) + persist — commits immediately (PaymentSagaSteps)
 *   2. Call payment gateway (sync — @CircuitBreaker/@Retry/@Bulkhead on adapter), NO local transaction held
 *   3a. Success → markSucceeded + persist + publish PaymentSucceededEvent — commits immediately
 *   3b. Failure → markFailed/markFailedAmbiguous + persist + publish PaymentFailedEvent — commits immediately
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
 * <p><strong>Card mode</strong> ({@code payment.mode=card}) stops after step 1½: the gateway
 * only opens a PaymentIntent ({@link PaymentGatewayPort#createIntent}) and the row keeps its id
 * and client secret ({@link Payment#attachIntent}). The customer confirms it in the browser, and
 * step 3 happens later, from whichever of three places learns the outcome first -- the Stripe
 * webhook, {@link #syncWithGateway} when the storefront reports the confirmation, or
 * {@code PaymentWindowExpiryJob} when the window closes. All three go through the same
 * {@code markSucceeded}/{@code markFailed} steps and raise the same events, so booking-service
 * cannot tell the two modes apart.
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
public class PaymentService implements InitiatePaymentUseCase, GetPaymentUseCase, RetryPaymentUseCase,
        RefundPaymentUseCase, SyncPaymentUseCase, ExpireCardPaymentsUseCase {

    private static final String IDEMPOTENCY_KEY_PREFIX = "payment:idempotency:booking:";

    // Short backoff for persisting an already-successful charge — this is retrying our own DB
    // write, not the gateway call, so attempts stay few and fast.
    private static final int PERSIST_MAX_ATTEMPTS = 3;
    private static final Duration PERSIST_RETRY_BACKOFF = Duration.ofMillis(200);

    private final PaymentSagaSteps sagaSteps;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentIdempotencyPort idempotencyPort;
    private final PaymentReconciliationPort reconciliationPort;
    private final PaymentModeProperties paymentMode;

    @Override
    public String execute(InitiatePaymentCommand command) {
        String bookingId = command.bookingId();
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + bookingId;

        // acquire() checks this instance's local cache before Redis — a Cached result skips
        // Redis AND Postgres entirely; AlreadyHeld means Redis confirms a duplicate but this
        // instance doesn't yet know the paymentId, so no DB write is attempted either way.
        PaymentIdempotencyResult idempotencyResult = idempotencyPort.acquire(idempotencyKey);
        if (idempotencyResult instanceof PaymentIdempotencyResult.Cached cached) {
            return cached.paymentId();
        }
        if (idempotencyResult instanceof PaymentIdempotencyResult.AlreadyHeld) {
            return existingPaymentIdOrThrow(bookingId, idempotencyKey);
        }

        // Step 1: create + persist payment in INITIATED state — commits immediately.
        // tryInsert flushes-and-commits so a unique-constraint violation (the fail-open race
        // window when Redis was down) surfaces here, before any gateway charge is attempted.
        //
        // Both exits below hand the guard back, because this call was granted it (Acquired) and is
        // leaving without a payment: nothing is in flight for the guard to protect, so keeping it
        // would answer the next attempt "already being processed" about a payment that does not
        // exist and never will. That answer is not merely unhelpful — PaymentController#initiate
        // turns this exception into 202 Accepted, which booking-service reads as confirmation that
        // a charge is running and accepts the booking as PENDING_PAYMENT, leaving a booking that no
        // event and no reconciliation query can ever resolve. The AlreadyHeld branch above is the
        // one exit that must NOT release: that guard belongs to a different attempt.
        PaymentSagaSteps.InitiateOutcome outcome;
        try {
            outcome = sagaSteps.tryInitiate(bookingId, command.customerEmail(), command.amount(), command.currency());
        } catch (RuntimeException e) {
            idempotencyPort.release(idempotencyKey);
            throw e;
        }
        if (outcome instanceof PaymentSagaSteps.InitiateOutcome.AlreadyExists) {
            try {
                return existingPaymentIdOrThrow(bookingId, idempotencyKey);
            } catch (RuntimeException e) {
                // DuplicatePaymentException, because tryInsert maps every
                // DataIntegrityViolationException to AlreadyExists and a constraint other than
                // uq_payments_booking_id reaches here with no row to find — but the lookup itself
                // can fail too, and that exit is no more entitled to keep the guard than this one.
                idempotencyPort.release(idempotencyKey);
                throw e;
            }
        }
        String paymentId = ((PaymentSagaSteps.InitiateOutcome.Created) outcome).paymentId();
        // paymentId is a durable, immutable identity fact from this point on regardless of the
        // payment's eventual SUCCEEDED/FAILED outcome — safe to cache immediately.
        idempotencyPort.remember(idempotencyKey, paymentId);
        log.info("Payment initiated: id={}, bookingId={}", paymentId, bookingId);

        if (paymentMode.isCardMode()) {
            openIntentForCustomer(paymentId, bookingId, command.amount(), command.currency());
            return paymentId;
        }

        // Step 2: charge via the gateway (REST call, no local transaction). A failure here means
        // the charge itself never went through (or was definitively declined) — safe to mark FAILED.
        String gatewayTxId;
        try {
            // A payment this method just created is always on attempt 0, whose key is the bare
            // bookingId (Payment#chargeIdempotencyKey) -- pinned by PaymentTest so the two cannot
            // drift apart into charging a fresh payment under a key a retry has already used.
            gatewayTxId = paymentGatewayPort.charge(bookingId, bookingId, command.amount(), command.currency());
        } catch (Exception e) {
            log.error("Payment charge failed: id={}, reason={}", paymentId, e.getMessage());
            if (isDeclinedException(e)) {
                sagaSteps.markFailed(paymentId, e.getMessage());
            } else {
                sagaSteps.markFailedAmbiguous(paymentId, e.getMessage());
            }
            return paymentId;
        }

        // Step 3: persist the successful charge outcome — entirely separate from step 2's
        // try/catch (see class javadoc): the customer is already charged at this point.
        persistSucceededOutcome(paymentId, bookingId, gatewayTxId, command.amount(), command.currency());
        return paymentId;
    }

    /**
     * Card mode's step 2: open the intent and remember it. Nothing has been charged, so a gateway
     * failure here is safe to record as FAILED -- booking-service then cancels the booking and
     * releases the seats, exactly as it would for a declined auto-mode charge. An intent that was
     * opened but could not be persisted is closed again so it cannot be confirmed by a client that
     * somehow learned its secret; if even that fails, the expiry job never sees the row (no
     * clientSecret), and the intent simply lapses at the gateway.
     */
    private void openIntentForCustomer(String paymentId, String bookingId, BigDecimal amount, String currency) {
        PaymentGatewayPort.IntentHandle intent;
        try {
            intent = paymentGatewayPort.createIntent(bookingId, bookingId, amount, currency);
        } catch (Exception e) {
            log.error("Opening payment intent failed: id={}, reason={}", paymentId, e.getMessage());
            if (isDeclinedException(e)) {
                sagaSteps.markFailed(paymentId, e.getMessage());
            } else {
                sagaSteps.markFailedAmbiguous(paymentId, e.getMessage());
            }
            return;
        }
        try {
            sagaSteps.attachIntent(paymentId, intent.gatewayIntentId(), intent.clientSecret());
            log.info("Payment intent opened for customer: id={}, bookingId={}, gatewayIntentId={}, window={}m",
                    paymentId, bookingId, intent.gatewayIntentId(), paymentMode.card().windowMinutes());
        } catch (Exception e) {
            log.error("Persisting payment intent failed: id={}, gatewayIntentId={}, reason={}",
                    paymentId, intent.gatewayIntentId(), e.getMessage());
            try {
                paymentGatewayPort.cancelIntent(intent.gatewayIntentId());
            } catch (Exception cancelEx) {
                log.warn("Could not close orphaned intent {}: {}", intent.gatewayIntentId(), cancelEx.getMessage());
            }
            sagaSteps.markFailedAmbiguous(paymentId, e.getMessage());
        }
    }

    @Override
    public Optional<Payment> syncWithGateway(String bookingId) {
        Optional<Payment> found = paymentRepository.findByBookingId(bookingId);
        if (found.isEmpty()) {
            return found;
        }
        Payment payment = found.get();
        if (payment.getStatus() != PaymentStatus.INITIATED || !payment.isCardMode()) {
            return found;
        }
        PaymentGatewayPort.IntentSnapshot snapshot = paymentGatewayPort.retrieveIntent(payment.getGatewayIntentId());
        recordIntentOutcome(payment, snapshot, "sync");
        return paymentRepository.findByBookingId(bookingId);
    }

    /**
     * The cancel is asked of the gateway, not assumed: a customer who confirmed in the last second
     * has a succeeded intent the gateway refuses to cancel, and {@link PaymentGatewayPort#cancelIntent}
     * reports that as SUCCEEDED so the payment is recorded as paid instead of expired. A PROCESSING
     * intent (bank still deciding) is left for the next run. One payment's failure (gateway down,
     * DB hiccup) must not stop the rest of the batch; that row stays INITIATED and is picked up again.
     */
    @Override
    public int closeExpiredWindows(Instant openedBefore, int batchSize) {
        List<Payment> expired = paymentRepository.findOpenCardPaymentsCreatedBefore(openedBefore, batchSize);
        if (expired.isEmpty()) {
            return 0;
        }
        log.info("Closing {} card payment window(s) opened before {}", expired.size(), openedBefore);
        int closed = 0;
        for (Payment payment : expired) {
            try {
                PaymentGatewayPort.IntentSnapshot snapshot = paymentGatewayPort.cancelIntent(payment.getGatewayIntentId());
                if (snapshot.outcome() == PaymentGatewayPort.IntentOutcome.CANCELED) {
                    snapshot = new PaymentGatewayPort.IntentSnapshot(snapshot.outcome(),
                            "Payment window of " + paymentMode.card().windowMinutes() + " minutes expired");
                    closed++;
                }
                recordIntentOutcome(payment, snapshot, "expiry");
            } catch (Exception e) {
                log.error("Could not close payment window: id={}, bookingId={}, gatewayIntentId={}: {}",
                        payment.getPaymentId(), payment.getBookingId(), payment.getGatewayIntentId(), e.getMessage());
            }
        }
        return closed;
    }

    /**
     * Shared by {@link #syncWithGateway} and {@link #closeExpiredWindows}: turns what the gateway
     * says about a card-mode intent into the payment's own state. Terminal outcomes go through the
     * same saga steps as an auto-mode charge; an open intent is left open. The gateway's
     * last-attempt error is kept for the status endpoint either way, since that is what the
     * customer at the form sees.
     */
    private void recordIntentOutcome(Payment payment, PaymentGatewayPort.IntentSnapshot snapshot, String source) {
        String paymentId = payment.getPaymentId();
        switch (snapshot.outcome()) {
            case SUCCEEDED -> {
                persistSucceededOutcome(paymentId, payment.getBookingId(), payment.getGatewayIntentId(),
                        payment.getAmount(), payment.getCurrency());
                log.info("Card payment succeeded ({}): id={}, bookingId={}", source, paymentId, payment.getBookingId());
            }
            case CANCELED -> {
                sagaSteps.markFailed(paymentId, snapshot.failureMessage() == null
                        ? "Payment was cancelled before it was completed"
                        : snapshot.failureMessage());
                log.info("Card payment cancelled ({}): id={}, bookingId={}", source, paymentId, payment.getBookingId());
            }
            case OPEN -> {
                if (snapshot.failureMessage() != null && !snapshot.failureMessage().equals(payment.getFailureReason())) {
                    sagaSteps.noteAttemptFailure(paymentId, snapshot.failureMessage());
                    log.info("Card payment attempt declined, intent still open ({}): id={}, reason={}",
                            source, paymentId, snapshot.failureMessage());
                }
            }
            case PROCESSING -> log.info("Card payment still processing at the gateway ({}): id={}", source, paymentId);
        }
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
            } catch (InvalidPaymentStatusException e) {
                // Not a persistence failure: the row has already left INITIATED. In card mode the
                // webhook, the storefront's sync and the expiry job can all learn of the same
                // success within the same second, and whichever lost this race must not spend
                // three attempts failing the same guard and then file the charge as unreconciled.
                // The only states markSucceeded refuses are SUCCEEDED and REFUNDED (a definitive
                // FAILED cannot be reached once the gateway has said succeeded), so the charge is
                // already on record.
                log.info("Payment already recorded as terminal, success is a no-op: id={}, gatewayTxId={}: {}",
                        paymentId, gatewayTxId, e.getMessage());
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
     * Auto mode only -- a card-mode payment refuses this at {@link Payment#retry}, since its FAILED
     * means the window closed and the seats are gone.
     *
     * <p>Re-opens a FAILED payment (see {@link Payment#retry}) and re-attempts the gateway charge —
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
            gatewayTxId = paymentGatewayPort.charge(payment.chargeIdempotencyKey(), payment.getBookingId(),
                    payment.getAmount(), payment.getCurrency());
        } catch (Exception e) {
            log.error("Payment retry charge failed: id={}, reason={}", paymentId, e.getMessage());
            if (isDeclinedException(e)) {
                sagaSteps.markFailed(paymentId, e.getMessage());
            } else {
                sagaSteps.markFailedAmbiguous(paymentId, e.getMessage());
            }
            return Optional.of(paymentId);
        }

        persistSucceededOutcome(paymentId, payment.getBookingId(), gatewayTxId, payment.getAmount(), payment.getCurrency());
        return Optional.of(paymentId);
    }

    /**
     * Refunds the SUCCEEDED payment for a booking (see {@link RefundPaymentUseCase} javadoc for
     * the no-op cases). Same two-phase shape as {@link #execute}: the gateway refund call runs
     * with no local transaction held, and only its outcome is persisted.
     *
     * <p>Persisting that outcome gets the same retry-then-reconcile treatment as a successful
     * charge (see {@link #persistSucceededOutcome}). It used to be a single unguarded call,
     * documented as an accepted gap on the grounds that the path had no production traffic —
     * which stopped being true once {@code Booking#cancelDueToMatchCancellation} began raising
     * {@code RefundRequestedEvent} automatically for every paid booking on a cancelled match.
     * Stripe's own idempotency key ({@code "refund:" + paymentIntentId}, see
     * {@code StripeGatewayAdapter#refund}) already prevents a redelivered event from moving money
     * twice; what was missing was any way to notice a payment left stuck at SUCCEEDED after its
     * money had in fact been returned.
     */
    @Override
    public Optional<String> refund(RefundCommand command) {
        String bookingId = command.bookingId();
        Payment payment = paymentRepository.findByBookingId(bookingId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.SUCCEEDED) {
            log.info("Refund skipped for bookingId={}: no SUCCEEDED payment with a balance left (status={})",
                    bookingId, payment == null ? "none" : payment.getStatus());
            return Optional.empty();
        }

        // A request raised before requests carried an id can only have been a full refund, and a
        // payment is fully refunded at most once, so the payment's own id keys it uniquely.
        String refundRequestId = command.refundRequestId() != null
                ? command.refundRequestId()
                : "full:" + payment.getPaymentId();
        if (paymentRepository.hasRefund(refundRequestId)) {
            log.info("Refund request already applied, skipping the redelivery: bookingId={}, refundRequestId={}",
                    bookingId, refundRequestId);
            return Optional.empty();
        }

        BigDecimal refundable = payment.refundableAmount();
        BigDecimal amount = command.amount() == null ? refundable : command.amount().min(refundable);
        if (command.amount() != null && command.amount().compareTo(refundable) > 0) {
            log.warn("Refund request asks for more than is left on the payment, refunding what is left: "
                            + "bookingId={}, refundRequestId={}, requested={}, refundable={} {}",
                    bookingId, refundRequestId, command.amount(), refundable, payment.getCurrency());
        }
        if (amount.signum() <= 0) {
            log.info("Refund skipped for bookingId={}: nothing left to refund (refundRequestId={})",
                    bookingId, refundRequestId);
            return Optional.empty();
        }

        String gatewayRefundId;
        try {
            gatewayRefundId = paymentGatewayPort.refund(refundIdempotencyKey(payment, refundRequestId),
                    payment.getGatewayTransactionId(), amount, payment.getCurrency());
        } catch (Exception e) {
            log.error("Refund gateway call failed: paymentId={}, bookingId={}, refundRequestId={}, amount={} {}, reason={}",
                    payment.getPaymentId(), bookingId, refundRequestId, amount, payment.getCurrency(), e.getMessage(), e);
            throw new RuntimeException("Refund failed for bookingId=" + bookingId, e);
        }

        persistRefundedOutcome(payment, bookingId, refundRequestId, amount, gatewayRefundId, command.reason());
        return Optional.of(payment.getPaymentId());
    }

    // Per request, not per charge — see PaymentGatewayPort#refund.
    private static String refundIdempotencyKey(Payment payment, String refundRequestId) {
        return "refund:" + payment.getGatewayTransactionId() + ":" + refundRequestId;
    }

    /**
     * Mirror of {@link #persistSucceededOutcome} for a refund that the gateway has already issued:
     * retry a few times, then hand the outcome to {@link PaymentReconciliationPort} rather than
     * lose it. The money is already back with the customer at this point, so the one thing this
     * must never do is leave that fact recorded nowhere.
     */
    private void persistRefundedOutcome(Payment payment, String bookingId, String refundRequestId,
                                        BigDecimal amount, String gatewayRefundId, String reason) {
        String paymentId = payment.getPaymentId();
        for (int attempt = 1; attempt <= PERSIST_MAX_ATTEMPTS; attempt++) {
            try {
                sagaSteps.markRefunded(paymentId, refundRequestId, amount, gatewayRefundId, reason);
                log.info("Payment refunded: id={}, bookingId={}, refundRequestId={}, amount={} {}, gatewayRefundId={}",
                        paymentId, bookingId, refundRequestId, amount, payment.getCurrency(), gatewayRefundId);
                return;
            } catch (Exception e) {
                log.error("Persisting issued refund failed (attempt {}/{}): id={}, gatewayRefundId={}, reason={}",
                        attempt, PERSIST_MAX_ATTEMPTS, paymentId, gatewayRefundId, e.getMessage());
                if (attempt == PERSIST_MAX_ATTEMPTS) {
                    recordRefundForManualReconciliation(payment, bookingId, amount, gatewayRefundId, e.getMessage());
                    return;
                }
                sleep(PERSIST_RETRY_BACKOFF.multipliedBy(attempt));
            }
        }
    }

    // Same last-resort contract as recordForManualReconciliation: must not itself throw and
    // abandon the outcome with nothing but a log line. Records the amount of THIS refund, which
    // since partial refunds is no longer necessarily the whole payment.
    private void recordRefundForManualReconciliation(Payment payment, String bookingId, BigDecimal amount,
                                                      String gatewayRefundId, String failureReason) {
        try {
            reconciliationPort.recordUnpersistedRefund(payment.getPaymentId(), bookingId, gatewayRefundId,
                    amount, payment.getCurrency(), failureReason);
            log.error("Refund was issued at the gateway but could not be persisted after {} attempts — " +
                            "recorded for manual reconciliation: id={}, bookingId={}, gatewayRefundId={}",
                    PERSIST_MAX_ATTEMPTS, payment.getPaymentId(), bookingId, gatewayRefundId);
        } catch (Exception e) {
            log.error("CRITICAL: refund was issued at the gateway but could not be persisted NOR recorded " +
                            "for reconciliation — id={}, bookingId={}, gatewayRefundId={}, amount={} {}: {}",
                    payment.getPaymentId(), bookingId, gatewayRefundId,
                    amount, payment.getCurrency(), e.getMessage(), e);
        }
    }

    private boolean isDeclinedException(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof PaymentDeclinedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String existingPaymentIdOrThrow(String bookingId, String idempotencyKey) {
        Optional<String> paymentId = paymentRepository.findByBookingId(bookingId).map(Payment::getPaymentId);
        paymentId.ifPresent(id -> idempotencyPort.remember(idempotencyKey, id));
        return paymentId.orElseThrow(() -> new DuplicatePaymentException(
                "Payment for booking " + bookingId + " is already being processed"));
    }
}
