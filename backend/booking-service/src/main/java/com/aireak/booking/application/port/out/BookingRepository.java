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
     * @param limit caps how many rows a single reconciler run processes — under an extended
     *              ticket-inventory-service outage the backlog could otherwise grow unbounded
     *              and every scheduled run would re-walk the entire thing.
     */
    List<Booking> findConfirmedAwaitingInventoryConfirmation(Instant updatedBefore, int limit);

    /** Page of a customer's bookings, newest first. */
    List<Booking> findByCustomerId(String customerId, int page, int size);
    long countByCustomerId(String customerId);
}
