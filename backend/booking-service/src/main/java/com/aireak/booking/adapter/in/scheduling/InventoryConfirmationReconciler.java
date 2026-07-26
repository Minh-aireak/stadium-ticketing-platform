package com.aireak.booking.adapter.in.scheduling;

import com.aireak.booking.application.port.out.BookingRepository;
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

/**
 * Inbound scheduling adapter: backstop for the gap on
 * {@code TicketInventoryRestAdapter#confirmReservationFallback} — if the synchronous
 * confirmReservation REST call fails after a booking is already CONFIRMED (payment succeeded),
 * the seat's Redis hold is never turned into a permanent SOLD row and just expires via TTL,
 * silently freeing up an already-sold seat. This job retries confirmReservation for any
 * CONFIRMED booking whose inventory confirm never succeeded.
 *
 * <p>Only considers bookings last updated more than {@code graceMinutes} ago, so it never races
 * the synchronous attempt {@link BookingOrchestrationService#confirmBooking} makes as part of
 * handling the PAYMENT_SUCCEEDED event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryConfirmationReconciler {

    private final BookingRepository bookingRepository;
    private final BookingOrchestrationService bookingOrchestrationService;

    @Value("${booking.inventory-confirmation-reconciler.grace-minutes:5}")
    private long graceMinutes;

    // Caps how many bookings a single run retries — under an extended ticket-inventory-service
    // outage the backlog could otherwise grow unbounded and every run would re-walk all of it.
    // The next run picks up whatever's left; a real backlog just drains over several cycles.
    @Value("${booking.inventory-confirmation-reconciler.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${booking.inventory-confirmation-reconciler.fixed-delay-ms:300000}")
    public void reconcile() {
        Instant cutoff = Instant.now().minus(graceMinutes, ChronoUnit.MINUTES);
        List<Booking> pending = bookingRepository.findConfirmedAwaitingInventoryConfirmation(cutoff, batchSize);
        if (pending.isEmpty()) {
            return;
        }

        log.warn("Reconciling {} CONFIRMED booking(s) with unconfirmed inventory", pending.size());
        for (Booking booking : pending) {
            try {
                bookingOrchestrationService.retryInventoryConfirmation(booking);
            } catch (Exception e) {
                log.error("Inventory confirmation retry failed for booking {}: {}",
                        booking.getBookingId(), e.getMessage());
            }
        }
    }
}
