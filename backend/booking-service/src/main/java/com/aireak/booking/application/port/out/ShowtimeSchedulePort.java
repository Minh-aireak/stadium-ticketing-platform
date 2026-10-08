package com.aireak.booking.application.port.out;

import java.time.Instant;

/**
 * Outbound port: when a showtime kicks off, from match-catalog-service — the clock a paid ticket's
 * cancellation deadline counts back from (see {@code CancellationWindow}).
 *
 * <p>Asked at cancel time rather than copied onto the booking when it is created: a showtime's start
 * is fixed once it is created, but the catalog is the only service that owns it, and a paid
 * cancellation is rare enough that one read per cancel costs nothing worth denormalizing for.
 */
public interface ShowtimeSchedulePort {

    /**
     * @throws OutboundServiceUnavailableException match-catalog-service gave no usable answer — the
     *         deadline cannot be checked, so the cancel must not go ahead
     */
    Instant kickoffOf(String showtimeId);
}
