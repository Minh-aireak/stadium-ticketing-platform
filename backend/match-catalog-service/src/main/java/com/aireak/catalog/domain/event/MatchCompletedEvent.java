package com.aireak.catalog.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Raised when a Match transitions PUBLISHED → COMPLETED.
 *
 * <p>{@code showtimeIds} mirrors {@link MatchCancelledEvent} — ticket-inventory-service consumes
 * it to mark those showtimes permanently unbookable locally (see
 * {@code ShowtimeCatalogPort#markUnbookable}) without a network round trip to catalog-service.
 */
public record MatchCompletedEvent(
        String matchId,
        String homeTeam,
        String awayTeam,
        List<String> showtimeIds,
        Instant occurredAt
) {
    public MatchCompletedEvent(String matchId, String homeTeam, String awayTeam, List<String> showtimeIds) {
        this(matchId, homeTeam, awayTeam, showtimeIds, Instant.now());
    }
}
