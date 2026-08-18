package com.aireak.catalog.application.port.out;

import java.util.Optional;

/**
 * Outbound port: fast, always-current read of a showtime's available-seat count, kept fresh by
 * write-through from {@code SeatAvailabilityProjectionAdapter} on every processed
 * {@code SeatsSoldEvent} — independent of any cache of the rest of a Match's (rarely-changing)
 * data. {@code showtimes.available_seats} in Postgres remains the durable source of truth; this
 * is a read accelerator only, so callers must fall back to the DB value on a miss.
 */
public interface LiveSeatAvailabilityPort {

    /** Publishes the current available-seat count for {@code showtimeId}, overwriting any prior value. */
    void publish(String showtimeId, int availableSeats);

    /** The most recently published count for {@code showtimeId}, or empty if never published (or expired). */
    Optional<Integer> get(String showtimeId);
}
