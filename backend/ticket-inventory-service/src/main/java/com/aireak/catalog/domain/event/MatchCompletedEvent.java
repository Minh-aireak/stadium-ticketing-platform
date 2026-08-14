package com.aireak.catalog.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Local copy of match-catalog-service's {@code MatchCompletedEvent} — same fully-qualified name
 * and record shape, required by {@code EventEnvelope}'s {@code @JsonTypeInfo(use = Id.CLASS)}
 * polymorphic deserialization (see {@code KafkaConfig}). Consumed by
 * {@code MatchCompletedEventConsumer} to mark the listed showtimes permanently unbookable in the
 * local {@code ShowtimeCatalogPort} cache.
 */
public record MatchCompletedEvent(
        String matchId,
        String homeTeam,
        String awayTeam,
        List<String> showtimeIds,
        Instant occurredAt
) {
}
