package com.aireak.catalog.application.port.out;

import java.util.Collection;
import java.util.Map;

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

    /**
     * Most recently published counts for {@code showtimeIds}, resolved in a SINGLE round-trip.
     *
     * <p>Batched rather than per-showtime on purpose: the only caller overlays a whole page of
     * matches at once, and a per-id lookup made that one MGET into one round-trip per showtime
     * (20 matches x 3 showtimes = 60 sequential hops for one page).
     *
     * <p>Ids that were never published (or whose value expired) are simply absent from the
     * returned map — callers keep whatever seat count they already hold for those.
     */
    Map<String, Integer> getAll(Collection<String> showtimeIds);
}
