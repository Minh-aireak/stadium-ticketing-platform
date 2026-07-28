package com.aireak.catalog.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;

/** Inbound port: add a showtime to an existing match. */
public interface AddShowtimeUseCase {
    void addShowtime(String matchId, Instant startTime, String venueId, int totalSeats,
                      BigDecimal basePrice, String currency);
}
