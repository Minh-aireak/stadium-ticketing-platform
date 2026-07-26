package com.aireak.inventory.adapter.out.persistence.outbox;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.common.web.filter.CorrelationIdFilter;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.domain.event.SeatsReleasedEvent;
import com.aireak.inventory.domain.event.SeatsReservedEvent;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbox adapter implementing {@link DomainEventPublisher}: instead of talking to Kafka
 * directly, it writes a row to {@code outbox_events} — Debezium CDC tails that table and
 * produces to Kafka, see {@code infra/debezium/inventory-outbox-connector.json}.
 *
 * <p>Replaces the old {@code InventoryEventPublisher}, which called
 * {@code KafkaTemplate.send()} directly. For {@link SeatsSoldEvent}, that meant the Kafka
 * publish happened before the surrounding Postgres transaction (the SOLD write) actually
 * committed — a crash or failed commit right after the send could emit "sold" for a sale that
 * was never persisted, and a Kafka hiccup could silently drop the event with no retry. Writing
 * to this table instead keeps the event write inside the SAME transaction as the aggregate
 * save (see {@code SeatSaleConfirmer#confirmSale}), so the two can never disagree.
 *
 * <p>The written payload is the same {@link EventEnvelope} JSON that used to be sent directly
 * to Kafka, so consumers see an unchanged message shape. The Kafka message key changes from the
 * old (effectively random) {@code eventId} to {@code showtimeId} (the aggregate id) — same fix
 * applied when booking-service/identity-service migrated to outbox; no consumer of these topics
 * exists yet, so this is safe.
 *
 * <p>Hexagonal rule: this adapter is the ONLY place that knows about the outbox table and topic
 * names. Domain and application layers depend only on the {@link DomainEventPublisher} port.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher implements DomainEventPublisher {

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publishAll(List<Object> events) {
        events.forEach(this::publish);
    }

    private void publish(Object domainEvent) {
        String topic = resolveTopic(domainEvent);
        if (topic == null) {
            log.warn("No topic mapping for domain event type: {}", domainEvent.getClass().getSimpleName());
            return;
        }
        String aggregateId = resolveAggregateId(domainEvent);
        String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
        EventEnvelope<?> envelope = EventEnvelope.of(domainEvent, traceId);

        String payload = serialize(envelope);
        outboxEventJpaRepository.save(OutboxEventEntity.of(
                topic, aggregateId, domainEvent.getClass().getSimpleName(), payload, traceId));

        log.debug("Outbox row written: type={}, topic={}, aggregateId={}, eventId={}",
                domainEvent.getClass().getSimpleName(), topic, aggregateId, envelope.getEventId());
    }

    private String resolveTopic(Object event) {
        return switch (event) {
            case SeatsReservedEvent ignored -> KafkaTopics.SEATS_RESERVED;
            case SeatsReleasedEvent ignored -> KafkaTopics.SEATS_RELEASED;
            case SeatsSoldEvent ignored     -> KafkaTopics.SEATS_SOLD;
            default -> null;
        };
    }

    private String resolveAggregateId(Object event) {
        return switch (event) {
            case SeatsReservedEvent e -> e.showtimeId();
            case SeatsReleasedEvent e -> e.showtimeId();
            case SeatsSoldEvent e     -> e.showtimeId();
            default -> throw new IllegalArgumentException(
                    "No aggregate id mapping for domain event type: " + event.getClass().getSimpleName());
        };
    }

    private String serialize(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize domain event envelope", e);
        }
    }
}
