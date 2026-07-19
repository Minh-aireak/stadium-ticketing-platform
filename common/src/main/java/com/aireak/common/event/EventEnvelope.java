package com.aireak.common.event;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Generic event envelope wrapping any domain event payload.
 * All Kafka messages in this platform are wrapped in EventEnvelope.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code eventId}    — unique ID for idempotency (consumers dedupe by this)</li>
 *   <li>{@code eventType}  — fully-qualified event class name for deserialization</li>
 *   <li>{@code occurredAt} — when the event happened (domain time, not broker time)</li>
 *   <li>{@code traceId}    — correlation ID propagated from the originating HTTP request</li>
 *   <li>{@code payload}    — the actual domain event data</li>
 * </ul>
 *
 * @param <T> domain event payload type
 */
@Getter
public class EventEnvelope<T> {

    private final String eventId;
    private final String eventType;
    private final Instant occurredAt;
    private final String traceId;

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    private final T payload;

    // Jackson deserialization constructor
    protected EventEnvelope() {
        this.eventId = null;
        this.eventType = null;
        this.occurredAt = null;
        this.traceId = null;
        this.payload = null;
    }

    private EventEnvelope(String eventId, String eventType, Instant occurredAt, String traceId, T payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.traceId = traceId;
        this.payload = payload;
    }

    /**
     * Factory method — generates a new random eventId.
     */
    public static <T> EventEnvelope<T> of(T payload, String traceId) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                payload.getClass().getName(),
                Instant.now(),
                traceId,
                payload
        );
    }

    /**
     * Factory method with explicit eventId (for testing or replay).
     */
    public static <T> EventEnvelope<T> of(String eventId, T payload, String traceId) {
        return new EventEnvelope<>(
                eventId,
                payload.getClass().getName(),
                Instant.now(),
                traceId,
                payload
        );
    }
}
