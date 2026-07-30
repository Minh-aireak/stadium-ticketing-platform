package com.aireak.catalog.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Raised when a Match transitions to CANCELLED. Consumed by booking-service, which cancels every
 * active booking for the listed showtimes and refunds the ones that were already paid.
 */
public record MatchCancelledEvent(
        String matchId,
        String homeTeam,
        String awayTeam,
        List<String> showtimeIds,
        String reason,
        Instant occurredAt
) {
    public MatchCancelledEvent(String matchId, String homeTeam, String awayTeam,
                                List<String> showtimeIds, String reason) {
        this(matchId, homeTeam, awayTeam, showtimeIds, reason, Instant.now());
    }
}
