package com.aireak.booking.adapter.out.persistence.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "outbox_events")
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventEntity {

    @Id
    @GeneratedValue
    private UUID id;

    /** Target Kafka topic, e.g. {@code booking.booking.confirmed}. */
    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    /** Business aggregate id (bookingId) — used as the Kafka message key. */
    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    /** Simple domain event class name, e.g. {@code BookingConfirmedEvent}. */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** Serialized {@code EventEnvelope} JSON — byte-identical to the prior Kafka message body. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static OutboxEventEntity of(String aggregateType, String aggregateId,
                                        String eventType, String payload, String traceId) {
        return new OutboxEventEntity(null, aggregateType, aggregateId, eventType, payload, traceId, Instant.now());
    }
}
