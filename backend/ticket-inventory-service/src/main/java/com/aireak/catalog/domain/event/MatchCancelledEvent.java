package com.aireak.catalog.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Local copy of match-catalog-service's {@code MatchCancelledEvent} — same fully-qualified name
 * and record shape, required by {@code EventEnvelope}'s {@code @JsonTypeInfo(use = Id.CLASS)}
 * polymorphic deserialization (see {@code KafkaConfig}, and booking-service's mirror of the same
 * event for the established pattern). Consumed by {@code MatchCancelledEventConsumer} to mark the
 * listed showtimes permanently unbookable in the local {@code ShowtimeCatalogPort} cache.
 */
public record MatchCancelledEvent(
        String matchId,
        String homeTeam,
        String awayTeam,
        List<String> showtimeIds,
        String reason,
        Instant occurredAt
) {
}
