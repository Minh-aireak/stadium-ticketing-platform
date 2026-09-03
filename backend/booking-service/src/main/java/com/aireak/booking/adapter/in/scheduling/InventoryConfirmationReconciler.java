package com.aireak.booking.adapter.in.scheduling;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.booking.application.service.BookingOrchestrationService.InventoryConfirmationOutcome;
import com.aireak.booking.domain.model.Booking;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * <p><strong>A retry is not always the answer</strong>, which is the reason this job publishes two
 * gauges rather than only logging. A confirm that fails because ticket-inventory-service is
 * restarting clears itself on the next run; a confirm refused because the seats are SOLD to a
 * different booking never will, and by the time that happens the customer has paid and has their
 * confirmation email. Those bookings are recorded (see
 * {@code BookingOrchestrationService#recordInventorySaleRefused}), drop out of the query below,
 * and are counted into {@code booking.inventory.sale.refused.count} instead — the same shape, and
 * for the same reason, as payment-service's {@code payment.unreconciled.count}: a log line only
 * alerts someone who is already reading logs, and this is the one failure on this path that
 * nothing but a human can resolve.
 */
@Slf4j
@Component
public class InventoryConfirmationReconciler {

    private final BookingRepository bookingRepository;
    private final BookingOrchestrationService bookingOrchestrationService;
    // Last observed counts, held so the gauges have something to read between scans. Both are
    // deliberately rewritten on every run, including a clean one, so a backlog somebody has since
    // resolved does not leave the alert firing.
    private final AtomicInteger unconfirmedCount = new AtomicInteger();
    private final AtomicInteger saleRefusedCount = new AtomicInteger();

    public InventoryConfirmationReconciler(BookingRepository bookingRepository,
                                           BookingOrchestrationService bookingOrchestrationService,
                                           MeterRegistry meterRegistry) {
        this.bookingRepository = bookingRepository;
        this.bookingOrchestrationService = bookingOrchestrationService;
        meterRegistry.gauge("booking.inventory.unconfirmed.count", unconfirmedCount);
        meterRegistry.gauge("booking.inventory.sale.refused.count", saleRefusedCount);
    }

    @Value("${booking.inventory-confirmation-reconciler.grace-minutes:5}")
    private long graceMinutes;

    // Caps how many bookings a single run retries — under an extended ticket-inventory-service
    // outage the backlog could otherwise grow unbounded and every run would re-walk all of it.
    // The next run picks up whatever's left; a real backlog just drains over several cycles.
    // That claim holds only because a booking that can never be confirmed leaves the query for
    // good rather than sitting in it: the query has a Limit and no ordering, so rows that never
    // resolve would otherwise fill the batch and starve every genuine one out of it permanently.
    @Value("${booking.inventory-confirmation-reconciler.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${booking.inventory-confirmation-reconciler.fixed-delay-ms:300000}")
    @SchedulerLock(name = "booking-inventoryConfirmationReconciler", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        Instant cutoff = Instant.now().minus(graceMinutes, ChronoUnit.MINUTES);
        List<Booking> pending = bookingRepository.findConfirmedAwaitingInventoryConfirmation(cutoff, batchSize);
        unconfirmedCount.set(pending.size());

        if (pending.isEmpty()) {
            // Still publishes: this is the run on which a cleared backlog stops alerting.
            publishRefusedCount();
            return;
        }

        log.warn("Reconciling {} CONFIRMED booking(s) with unconfirmed inventory", pending.size());
        int confirmed = 0;
        int refused = 0;
        for (Booking booking : pending) {
            try {
                InventoryConfirmationOutcome outcome =
                        bookingOrchestrationService.retryInventoryConfirmation(booking);
                if (outcome == InventoryConfirmationOutcome.CONFIRMED) {
                    confirmed++;
                } else if (outcome == InventoryConfirmationOutcome.REFUSED) {
                    refused++;
                }
            } catch (Exception e) {
                // retryInventoryConfirmation does not throw today — confirmInventoryReservation
                // catches Exception on every path — so this is a guard against a future edit
                // rather than a branch anything currently reaches. Without it, one booking would
                // strand every booking behind it in the list, and the ones stranded would be
                // invisible: they are simply absent from the counts below.
                log.error("Inventory confirmation retry failed for booking {}: {}",
                        booking.getBookingId(), e.getMessage());
            }
        }

        // The summary the WARN above never gave. "Reconciling 12 bookings" reads identically on
        // the run that fixes all twelve and on the thousandth run that fixes none of them.
        log.warn("Inventory confirmation pass finished: {} of {} confirmed, {} refused for good, {} still unresolved",
                confirmed, pending.size(), refused, pending.size() - confirmed - refused);
        publishRefusedCount();
    }

    /**
     * Bookings ticket-inventory-service has refused for good: paid for, CONFIRMED, seats never
     * sold, and nothing left to retry. Read on every pass rather than accumulated in memory so a
     * restart, a second replica, or a manual fix in the database is reflected on the next run.
     */
    private void publishRefusedCount() {
        long refusedTotal = bookingRepository.countInventorySaleRefused();
        saleRefusedCount.set((int) refusedTotal);
        if (refusedTotal > 0) {
            log.error("ALERT: {} CONFIRMED booking(s) were paid for but their seats were never sold and " +
                    "never can be — each needs a refund or a reseat by hand", refusedTotal);
        }
    }
}
