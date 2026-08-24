package com.aireak.catalog.application.port.out;

import java.util.Optional;

/**
 * Outbound port: reads a showtime's committed {@code available_seats} straight from Postgres,
 * bypassing every cache.
 *
 * <p>Deliberately separate from {@link SeatAvailabilityProjectionPort} even though one adapter
 * implements both: this is the recovery path's read, used only when the Redis counter needs to be
 * re-derived from the source of truth. Callers that reconcile should not have to depend on an
 * interface that can also mutate.
 */
public interface ShowtimeSeatCountPort {

    /** Committed available-seat count, or empty when no such showtime exists. */
    Optional<Integer> findAvailableSeats(String showtimeId);
}
