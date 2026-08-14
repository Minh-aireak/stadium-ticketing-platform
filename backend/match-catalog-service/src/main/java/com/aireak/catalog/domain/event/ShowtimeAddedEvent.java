package com.aireak.catalog.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Raised when a Showtime is added to a Match. Consumed by ticket-inventory-service
 * (see {@code ShowtimeAddedEventConsumer}) to generate the seat map for the showtime —
 * catalog-service owns pricing, inventory-service owns seat layout/allocation.
 *
 * <p>{@code startTime} lets ticket-inventory-service remember it locally (see
 * {@code ShowtimeCatalogPort#rememberStartTime}) and reject a reserve/hold for an
 * already-started showtime without a network round trip to catalog-service — the fact never
 * changes once the showtime is created, so it's safe to cache indefinitely.
 */
public record ShowtimeAddedEvent(
        String matchId,
        String showtimeId,
        String stadiumId,
        Instant startTime,
        int totalSeats,
        BigDecimal basePrice,
        String currency,
        Instant occurredAt
) {
    public ShowtimeAddedEvent(String matchId, String showtimeId, String stadiumId, Instant startTime,
                               int totalSeats, BigDecimal basePrice, String currency) {
        this(matchId, showtimeId, stadiumId, startTime, totalSeats, basePrice, currency, Instant.now());
    }
}
