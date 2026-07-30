package com.aireak.catalog.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Local copy of match-catalog-service's {@code MatchCancelledEvent} — same fully-qualified name
 * and record shape, required by {@code EventEnvelope}'s {@code @JsonTypeInfo(use = Id.CLASS)}
 * polymorphic deserialization (see booking-service's {@code KafkaConfig}). Consumed by
 * {@code MatchCancelledConsumer} to cancel active bookings for the listed showtimes and refund
 * the ones already paid.
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
