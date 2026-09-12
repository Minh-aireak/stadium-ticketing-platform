package com.aireak.booking.adapter.in.scheduling;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.PaymentPort;
import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.booking.domain.model.Booking;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Inbound scheduling adapter: backstop for a booking that never receives a payment result.
 * {@link com.aireak.booking.adapter.in.messaging.PaymentResultConsumer} drives the saga to
 * CONFIRMED/CANCELLED on {@code PaymentSucceededEvent}/{@code PaymentFailedEvent} — but that
 * event can be lost (a dropped outbox row, a permanently-failing consumption that lands on the
 * DLT, an outage) and leave a booking stuck in PENDING_PAYMENT forever with seats held and a
 * customer never charged-or-refused.
 *
 * <p>This job queries payment-service directly for the true outcome ({@link PaymentPort#checkOutcome})
 * and drives the SAME saga methods {@code PaymentResultConsumer} uses
 * ({@link BookingOrchestrationService#confirmBooking}/{@link BookingOrchestrationService#cancelBookingOnPaymentFailure}),
 * so it can never race or double-apply an outcome the Kafka consumer (or a concurrent run) already
 * applied. Their guards are not the same guard, though, and the difference is what makes the DRAFT
 * sweep below work at all: {@code confirmBooking} refuses anything that is not PENDING_PAYMENT,
 * while {@code cancelBookingOnPaymentFailure} refuses only CONFIRMED and CANCELLED — which is why
 * it can be handed a DRAFT booking and actually cancel it. This javadoc used to credit both with
 * the stricter guard; had that been true, {@link #reconcileDraftBookings} would have been a silent
 * no-op logging a warning about the same stuck bookings every five minutes forever.
 *
 * <p>Only considers bookings last updated more than {@code graceMinutes} ago, so it never races
 * the normal, still-in-flight case where payment-service simply hasn't responded yet.
 *
 * <p>There is a third answer payment-service can give besides an outcome and no answer at all:
 * that it has no payment for this booking ({@link PaymentPort.PaymentOutcome#NOT_FOUND}). That
 * is what a booking looks like when payment-service was down for Step 4 of the saga — the
 * booking committed PENDING_PAYMENT, the initiate call never arrived, and once payment-service
 * is back it truthfully reports nothing. Until this job learned to act on it, such a booking was
 * examined every run, logged as "still unresolved", and left PENDING_PAYMENT forever: its seats
 * came free when the inventory hold expired, but the row — and the customer's "awaiting
 * payment" ticket — never moved. Now a booking that is still NOT_FOUND {@code abandonAfterMinutes}
 * after it was created is cancelled through the same path a FAILED payment takes, which also
 * releases the seats. The threshold is set to the inventory hold TTL rather than the
 * milliseconds it would take to be safe, so that by the time this fires the checkout it belonged
 * to is long gone either way. Should a PaymentSucceededEvent nonetheless turn up for a booking
 * cancelled here, {@link BookingOrchestrationService#confirmBooking} answers it with a refund.
 */
@Slf4j
@Component
public class BookingReconciliationJob {

    private final BookingRepository bookingRepository;
    private final PaymentPort paymentPort;
    private final BookingOrchestrationService bookingOrchestrationService;
    // Counted rather than gauged: each abandonment is a customer whose checkout silently died,
    // and the interesting number is how often that happens, not how many are pending right now.
    private final Counter abandonedCounter;

    public BookingReconciliationJob(BookingRepository bookingRepository, PaymentPort paymentPort,
                                    BookingOrchestrationService bookingOrchestrationService,
                                    MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.paymentPort = paymentPort;
        this.bookingOrchestrationService = bookingOrchestrationService;
        this.abandonedCounter = meterRegistry.counter("booking.payment.abandoned");
    }

    @Value("${booking.payment-reconciliation-job.grace-minutes:5}")
    private long graceMinutes;

    // How long a PENDING_PAYMENT booking may keep answering NOT_FOUND before it is given up on.
    // Measured from the booking's creation, not its last update: a booking reaches
    // PENDING_PAYMENT within seconds of being created and is not touched again until it
    // resolves, so the two are the same clock, and creation is the one the domain object carries.
    @Value("${booking.payment-reconciliation-job.abandon-after-minutes:10}")
    private long abandonAfterMinutes;

    // Caps how many bookings a single run reconciles — same rationale as
    // InventoryConfirmationReconciler's batch-size: an extended payment-service outage shouldn't
    // make every run re-walk an unbounded backlog. The next run picks up whatever's left.
    @Value("${booking.payment-reconciliation-job.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${booking.payment-reconciliation-job.fixed-delay-ms:300000}")
    @SchedulerLock(name = "booking-paymentReconciliation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        Instant now = Instant.now();
        Instant graceCutoff = now.minus(graceMinutes, ChronoUnit.MINUTES);
        Instant abandonCutoff = now.minus(abandonAfterMinutes, ChronoUnit.MINUTES);
        reconcileDraftBookings(graceCutoff);
        reconcilePendingPaymentBookings(graceCutoff, abandonCutoff);
    }

    private void reconcileDraftBookings(Instant cutoff) {
        List<Booking> drafts = bookingRepository.findDraftOlderThan(cutoff, batchSize);
        if (drafts.isEmpty()) {
            return;
        }

        log.warn("Reconciling {} DRAFT booking(s) stuck past grace period", drafts.size());
        for (Booking booking : drafts) {
            try {
                String bookingId = booking.getBookingId();
                log.warn("Reconciling DRAFT booking {} as EXPIRED; cancelling and releasing seats if held", bookingId);
                bookingOrchestrationService.cancelBookingOnPaymentFailure(bookingId, "Draft booking expired");
            } catch (Exception e) {
                log.error("DRAFT reconciliation failed for booking {}: {}",
                        booking.getBookingId(), e.getMessage());
            }
        }
    }

    private void reconcilePendingPaymentBookings(Instant graceCutoff, Instant abandonCutoff) {
        List<Booking> pending = bookingRepository.findPendingPaymentOlderThan(graceCutoff, batchSize);
        if (pending.isEmpty()) {
            return;
        }

        log.warn("Reconciling {} PENDING_PAYMENT booking(s) with no resolved payment outcome", pending.size());
        for (Booking booking : pending) {
            try {
                reconcileOnePendingPayment(booking, abandonCutoff);
            } catch (Exception e) {
                log.error("Payment reconciliation failed for booking {}: {}",
                        booking.getBookingId(), e.getMessage());
            }
        }
    }

    private void reconcileOnePendingPayment(Booking booking, Instant abandonCutoff) {
        String bookingId = booking.getBookingId();
        switch (paymentPort.checkOutcome(bookingId)) {
            case SUCCEEDED -> {
                log.warn("Reconciled booking {} as SUCCEEDED via payment-service query; confirming", bookingId);
                bookingOrchestrationService.confirmBooking(bookingId);
            }
            case FAILED -> {
                log.warn("Reconciled booking {} as FAILED via payment-service query; cancelling", bookingId);
                bookingOrchestrationService.cancelBookingOnPaymentFailure(bookingId, "Payment failed (reconciled)");
            }
            case NOT_FOUND -> abandonIfOldEnough(booking, abandonCutoff);
            case IN_FLIGHT, UNKNOWN ->
                log.debug("Payment outcome for booking {} still unresolved; will retry next run", bookingId);
        }
    }

    private void abandonIfOldEnough(Booking booking, Instant abandonCutoff) {
        String bookingId = booking.getBookingId();
        if (booking.getCreatedAt().isAfter(abandonCutoff)) {
            log.debug("payment-service has no payment for booking {} yet; created {}, giving it until {}",
                    bookingId, booking.getCreatedAt(),
                    booking.getCreatedAt().plus(abandonAfterMinutes, ChronoUnit.MINUTES));
            return;
        }
        log.warn("payment-service has no payment for booking {} created {} — no charge was ever " +
                "initiated; cancelling and releasing seats", bookingId, booking.getCreatedAt());
        bookingOrchestrationService.cancelBookingOnPaymentFailure(bookingId, "No payment was ever initiated");
        abandonedCounter.increment();
    }
}
