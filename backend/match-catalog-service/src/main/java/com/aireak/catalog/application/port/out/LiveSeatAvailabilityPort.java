package com.aireak.catalog.application.port.out;

import java.util.Collection;
import java.util.Map;

/**
 * Outbound port: fast, always-current READ of a showtime's available-seat count, independent of any
 * cache of the rest of a Match's (rarely-changing) data.
 *
 * <p>Read-only by design. Every write to the counter goes through
 * {@link SeatCounterSeedPort} and {@link SeatCounterUpdatePort}, the only things that may touch it — seeding it when
 * a showtime is created, and decrementing it atomically as sales are projected. Splitting the two
 * is what keeps the atomicity meaningful: an unconditional "overwrite with this number" reachable
 * from the read side would let a caller undo a decrement it never saw, which is exactly the
 * lost-update bug the Lua script exists to prevent.
 *
 * <p>{@code showtimes.available_seats} in Postgres remains the durable source of truth; this is a
 * read accelerator, so callers must fall back to the DB value on a miss.
 */
public interface LiveSeatAvailabilityPort {

    /**
     * Current counts for {@code showtimeIds}, resolved in a SINGLE round-trip.
     *
     * <p>Batched rather than per-showtime on purpose: the only caller overlays a whole page of
     * matches at once, and a per-id lookup made that one MGET into one round-trip per showtime
     * (20 matches x 3 showtimes = 60 sequential hops for one page).
     *
     * <p>Ids with no counter (never seeded, or expired) are simply absent from the returned map —
     * callers keep whatever seat count they already hold for those.
     */
    Map<String, Integer> getAll(Collection<String> showtimeIds);
}
