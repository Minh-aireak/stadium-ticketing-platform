package com.aireak.booking.adapter.in.scheduling;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.port.out.PaymentPort;
import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.booking.domain.model.Booking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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
 * ({@link BookingOrchestrationService#confirmBooking}/{@link BookingOrchestrationService#cancelBookingOnPaymentFailure})
 * — both already no-op if the booking isn't PENDING_PAYMENT anymore, so this job can never race
 * or double-apply an outcome the Kafka consumer (or a concurrent run) already applied.
 *
 * <p>Only considers bookings last updated more than {@code graceMinutes} ago, so it never races
 * the normal, still-in-flight case where payment-service simply hasn't responded yet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingReconciliationJob {

    private final BookingRepository bookingRepository;
    private final PaymentPort paymentPort;
    private final BookingOrchestrationService bookingOrchestrationService;

    @Value("${booking.payment-reconciliation-job.grace-minutes:5}")
    private long graceMinutes;

    // Caps how many bookings a single run reconciles — same rationale as
    // InventoryConfirmationReconciler's batch-size: an extended payment-service outage shouldn't
    // make every run re-walk an unbounded backlog. The next run picks up whatever's left.
    @Value("${booking.payment-reconciliation-job.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${booking.payment-reconciliation-job.fixed-delay-ms:300000}")
    public void reconcile() {
        Instant cutoff = Instant.now().minus(graceMinutes, ChronoUnit.MINUTES);
        reconcileDraftBookings(cutoff);
        reconcilePendingPaymentBookings(cutoff);
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

    private void reconcilePendingPaymentBookings(Instant cutoff) {
        List<Booking> pending = bookingRepository.findPendingPaymentOlderThan(cutoff, batchSize);
        if (pending.isEmpty()) {
            return;
        }

        log.warn("Reconciling {} PENDING_PAYMENT booking(s) with no resolved payment outcome", pending.size());
        for (Booking booking : pending) {
            try {
                reconcileOnePendingPayment(booking);
            } catch (Exception e) {
                log.error("Payment reconciliation failed for booking {}: {}",
                        booking.getBookingId(), e.getMessage());
            }
        }
    }

    private void reconcileOnePendingPayment(Booking booking) {
        String bookingId = booking.getBookingId();
        Optional<PaymentPort.PaymentOutcome> outcome = paymentPort.checkOutcome(bookingId);
        if (outcome.isEmpty()) {
            log.debug("Payment outcome for booking {} still unresolved; will retry next run", bookingId);
            return;
        }

        switch (outcome.get()) {
            case SUCCEEDED -> {
                log.warn("Reconciled booking {} as SUCCEEDED via payment-service query; confirming", bookingId);
                bookingOrchestrationService.confirmBooking(bookingId);
            }
            case FAILED -> {
                log.warn("Reconciled booking {} as FAILED via payment-service query; cancelling", bookingId);
                bookingOrchestrationService.cancelBookingOnPaymentFailure(bookingId, "Payment failed (reconciled)");
            }
        }
    }
}
