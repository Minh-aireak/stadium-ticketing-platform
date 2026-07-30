package com.aireak.catalog.domain.event;

import java.time.Instant;

/** Raised when a Match transitions PUBLISHED → COMPLETED. */
public record MatchCompletedEvent(
        String matchId,
        String homeTeam,
        String awayTeam,
        Instant occurredAt
) {
    public MatchCompletedEvent(String matchId, String homeTeam, String awayTeam) {
        this(matchId, homeTeam, awayTeam, Instant.now());
    }
}
