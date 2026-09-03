package com.aireak.booking.application.port.out;

import com.aireak.booking.domain.model.Booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// Outbound port: Booking persistence.
public interface BookingRepository {
    void save(Booking booking);
    Optional<Booking> findById(String bookingId);
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /**
     * CONFIRMED bookings whose seat sale was never successfully finalized in
     * ticket-inventory-service (see {@code TicketInventoryRestAdapter#confirmReservationFallback}),
     * last updated before {@code updatedBefore}. Used by {@code InventoryConfirmationReconciler}
     * to retry only the ones that actually need it, not every CONFIRMED booking ever.
     *
     * <p>Excludes the ones ticket-inventory-service has refused for good — see
     * {@link #countInventorySaleRefused}. That exclusion is not a refinement, it is what makes
     * the cap below work: a booking whose confirm can never succeed would otherwise stay in this
     * result set for the life of the row.
     *
     * @param limit caps how many rows a single reconciler run processes — under an extended
     *              ticket-inventory-service outage the backlog could otherwise grow unbounded
     *              and every scheduled run would re-walk the entire thing. The next run picks up
     *              whatever is left, which only drains because there is no ordering here and
     *              nothing permanently unresolvable left to fill the batch with.
     */
    List<Booking> findConfirmedAwaitingInventoryConfirmation(Instant updatedBefore, int limit);

    /**
     * How many bookings ticket-inventory-service has given a FINAL refusal for -- their seats are
     * SOLD to a different booking, or the showtime has no inventory at all. These are paid,
     * CONFIRMED bookings that no retry can resolve, so they are deliberately NOT in
     * {@link #findConfirmedAwaitingInventoryConfirmation} above; this is what
     * {@code InventoryConfirmationReconciler} publishes as {@code booking.inventory.sale.refused}
     * so a human is actually told. Not paged: it is a count, and it should be zero.
     */
    long countInventorySaleRefused();

    /**
     * PENDING_PAYMENT bookings last updated before {@code updatedBefore} — a payment result
     * ({@code PaymentSucceededEvent}/{@code PaymentFailedEvent}) was never received for them, or
     * the resulting Kafka consumption failed silently. Used by {@code BookingReconciliationJob}
     * to query payment-service directly for the true outcome instead of waiting forever.
     *
     * @param limit caps how many bookings a single run reconciles — same rationale as
     *              {@link #findConfirmedAwaitingInventoryConfirmation}.
     */
    List<Booking> findPendingPaymentOlderThan(Instant updatedBefore, int limit);

    /**
     * DRAFT bookings last updated before {@code updatedBefore} — stuck in DRAFT prior to
     * initiating payment (e.g. crash or network failure after draft creation). Used by
     * {@code BookingReconciliationJob} to cancel stale draft bookings and release any seat holds.
     *
     * @param limit caps how many bookings a single run reconciles.
     */
    List<Booking> findDraftOlderThan(Instant updatedBefore, int limit);

    /** Page of a customer's bookings, newest first. */
    List<Booking> findByCustomerId(String customerId, int page, int size);
    long countByCustomerId(String customerId);

    /**
     * Every non-CANCELLED booking (DRAFT, PENDING_PAYMENT, or CONFIRMED) for a showtime — used by
     * {@code MatchCancelledConsumer} to find every booking that needs cancelling (and, for the
     * CONFIRMED ones, refunding) when the match those showtimes belong to is cancelled.
     */
    List<Booking> findActiveByShowtimeId(String showtimeId);
}
