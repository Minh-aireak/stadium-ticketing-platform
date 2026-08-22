package com.aireak.inventory.adapter.out.persistence.outbox;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.common.outbox.AbstractOutboxEventPublisher;
import com.aireak.common.outbox.OutboxEventJpaRepository;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.domain.event.SeatsReleasedEvent;
import com.aireak.inventory.domain.event.SeatsReservedEvent;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Outbox adapter implementing {@link DomainEventPublisher}: instead of talking to Kafka directly,
 * it writes a row to {@code outbox_events} in the SAME transaction as the SeatInventory aggregate
 * save — Debezium CDC tails that table and produces to Kafka, see
 * {@code infra/debezium/inventory-outbox-connector.json}.
 *
 * <p>The written payload is the same {@link EventEnvelope} JSON that used to be sent directly to
 * Kafka, so consumers see an unchanged message shape.
 *
 * <p>Hexagonal rule: this adapter is the ONLY place that knows about topic names, which is all
 * that is left here — {@link AbstractOutboxEventPublisher} owns the envelope/correlation-id/write
 * mechanism that every service shares.
 */
@Component
public class OutboxEventPublisher extends AbstractOutboxEventPublisher implements DomainEventPublisher {

    public OutboxEventPublisher(OutboxEventJpaRepository outboxEventJpaRepository, ObjectMapper objectMapper) {
        super(outboxEventJpaRepository, objectMapper);
    }

    @Override
    protected String resolveTopic(Object event) {
        return switch (event) {
            case SeatsReservedEvent ignored -> KafkaTopics.SEATS_RESERVED;
            case SeatsReleasedEvent ignored -> KafkaTopics.SEATS_RELEASED;
            case SeatsSoldEvent ignored -> KafkaTopics.SEATS_SOLD;
            default -> null;
        };
    }

    @Override
    protected String resolveAggregateId(Object event) {
        return switch (event) {
            case SeatsReservedEvent e -> e.showtimeId();
            case SeatsReleasedEvent e -> e.showtimeId();
            case SeatsSoldEvent e -> e.showtimeId();
            default -> throw new IllegalArgumentException(
                    "No aggregate id mapping for domain event type: " + event.getClass().getSimpleName());
        };
    }
}
