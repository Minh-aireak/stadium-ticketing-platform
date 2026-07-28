package com.aireak.catalog.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Raised when a Showtime is added to a Match. Consumed by ticket-inventory-service
 * (see {@code ShowtimeAddedEventConsumer}) to generate the seat map for the showtime —
 * catalog-service owns pricing, inventory-service owns seat layout/allocation.
 */
public record ShowtimeAddedEvent(
        String matchId,
        String showtimeId,
        int totalSeats,
        BigDecimal basePrice,
        String currency,
        Instant occurredAt
) {
    public ShowtimeAddedEvent(String matchId, String showtimeId, int totalSeats,
                               BigDecimal basePrice, String currency) {
        this(matchId, showtimeId, totalSeats, basePrice, currency, Instant.now());
    }
}
