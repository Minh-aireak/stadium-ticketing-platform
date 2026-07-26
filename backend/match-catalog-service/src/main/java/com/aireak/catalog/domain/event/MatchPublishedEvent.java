package com.aireak.catalog.domain.event;

import java.time.Instant;

/** Raised when a Match transitions DRAFT → PUBLISHED. Triggers Elasticsearch indexing. */
public record MatchPublishedEvent(
        String matchId,
        String homeTeam,
        String awayTeam,
        String competition,
        Instant occurredAt
) {
    public MatchPublishedEvent(String matchId, String homeTeam, String awayTeam, String competition) {
        this(matchId, homeTeam, awayTeam, competition, Instant.now());
    }
}
