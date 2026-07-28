package com.aireak.catalog.adapter.out.search;

import java.time.Instant;

/** Elasticsearch document shape for the "matches" index — search read model, not the write-side aggregate. */
record MatchDocument(
        String matchId,
        String homeTeam,
        String awayTeam,
        String competition,
        String status,
        Instant createdAt) {
}
