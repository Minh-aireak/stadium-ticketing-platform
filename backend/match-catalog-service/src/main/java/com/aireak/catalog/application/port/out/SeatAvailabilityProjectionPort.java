package com.aireak.catalog.application.port.out;

/**
 * Applies a sold-seat or returned-seat event to the catalog's durable read model —
 * {@code showtimes.available_seats} in Postgres — exactly once.
 *
 * <p>Only the database half. The live Redis counter is handled by
 * {@link SeatCounterUpdatePort}, and the two are sequenced by
 * {@code SoldSeatsProjectionService} and {@code ReturnedSeatsProjectionService}.
 */
public interface SeatAvailabilityProjectionPort {

    /** @return {@code false} when {@code eventId} had already been applied and was skipped. */
    boolean decrementAvailableSeats(String eventId, String showtimeId, int soldSeatCount);

    /**
     * Adds seats a cancelled booking put back on sale, capped at the showtime's total.
     *
     * @return {@code false} when {@code eventId} had already been applied and was skipped.
     */
    boolean incrementAvailableSeats(String eventId, String showtimeId, int returnedSeatCount);
}
