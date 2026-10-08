package com.aireak.catalog.application.port.in;

/**
 * Inbound port: project seats a cancelled paid booking put back on sale onto the catalog's
 * availability numbers — the {@code showtimes.available_seats} column, and the Redis counter
 * customers are shown. The inverse of {@link ApplySoldSeatsUseCase}, driven by the same consumer off
 * the same topic, so one showtime's sales and returns are applied in order.
 */
public interface ApplyReturnedSeatsUseCase {

    /**
     * @return {@code true} when this event moved the numbers, {@code false} when it had already
     *         been applied and was skipped
     */
    boolean applyReturnedSeats(String eventId, String showtimeId, int returnedSeatCount);
}
