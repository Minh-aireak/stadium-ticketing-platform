package com.aireak.catalog.application.port.out;

/**
 * Outbound port: brings a showtime's live seat counter into existence.
 *
 * <p>One method, one client ({@code ShowtimeSeatCounterInitializer}). Split out from the counter's
 * other operations so creating a showtime depends on "seed a counter" and nothing else — an admin
 * write has no business being able to reach a decrement.
 *
 * <p>See {@link SeatCounterUpdatePort} for why the counter has a single writer, and
 * {@code RedisSeatAvailabilityCounter} for the implementation all three counter ports share.
 */
public interface SeatCounterSeedPort {

    /**
     * Seeds a brand-new showtime's counter with its full capacity — called once, when the showtime
     * is created, so the counter exists before the first customer ever looks at it.
     *
     * <p>Seeds only when the key is absent. A retried or replayed initialization must never write
     * full capacity over a counter that has already sold seats, so an existing value is reported
     * back as {@link SeedResult#ALREADY_PRESENT} and left untouched.
     */
    SeedResult initialize(String showtimeId, int totalSeats);

    enum SeedResult {
        /** The key was absent and now holds the showtime's full capacity. */
        SEEDED,
        /** A counter was already there and was deliberately left alone. */
        ALREADY_PRESENT,
        /** Redis could not be reached; nothing was written. */
        UNAVAILABLE
    }
}
