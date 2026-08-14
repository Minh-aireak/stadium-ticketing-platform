package com.aireak.inventory.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Inbound port: generate the seat map for a showtime.
 * Triggered by {@code ShowtimeAddedEventConsumer} off catalog-service's {@code ShowtimeAddedEvent}.
 */
public interface GenerateSeatMapUseCase {
    void generate(String showtimeId, String stadiumId, Instant startTime, int expectedTotalSeats, BigDecimal basePrice);
}
