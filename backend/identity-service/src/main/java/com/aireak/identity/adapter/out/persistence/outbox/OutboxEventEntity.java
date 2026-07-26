package com.aireak.identity.adapter.out.persistence.outbox;

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

/**
 * JPA entity for the {@code outbox_events} table.
 *
 * <p>Rows written here are picked up by Debezium's CDC connector and routed to
 * Kafka via the outbox event router SMT — see
 * {@code identity-service/infra/debezium/identity-outbox-connector.json}.
 * The application never talks to Kafka directly; writing this row in the same
 * transaction as the aggregate save is what makes publication atomic.
 */
@Getter
@Entity
@Table(name = "outbox_events")
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventEntity {

    @Id
    @GeneratedValue
    private UUID id;

    /** Target Kafka topic, e.g. {@code identity.account.registered}. */
    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    /** Business aggregate id (accountId) — used as the Kafka message key. */
    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    /** Simple domain event class name, e.g. {@code AccountRegisteredEvent}. */
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
