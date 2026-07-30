package com.aireak.catalog.adapter.out.persistence.outbox;

import com.aireak.catalog.application.port.out.DomainEventPublisher;
import com.aireak.catalog.domain.event.MatchPublishedEvent;
import com.aireak.catalog.domain.event.ShowtimeAddedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbox adapter implementing {@link DomainEventPublisher}: instead of talking to Kafka
 * directly, it writes a row to {@code outbox_events} in the SAME transaction as the Match
 * aggregate save — Debezium CDC tails that table and produces to Kafka, see
 * {@code infra/debezium/catalog-outbox-connector.json}.
 *
 * <p>Replaces the old {@code CatalogEventPublisher}, which called {@code KafkaTemplate.send()}
 * directly inside {@code MatchCatalogService#publishMatch}'s {@code @Transactional} method — a
 * crash or broker hiccup between the DB commit and the Kafka send could silently drop
 * {@code MatchPublishedEvent}. Writing to this table instead keeps the event write inside the
 * same transaction as the Match status change, so the two can never disagree.
 *
 * <p>The written payload is the same {@link EventEnvelope} JSON that used to be sent directly
 * to Kafka, so consumers see an unchanged message shape. The Kafka message key changes from the
 * old (effectively random) {@code eventId} to {@code matchId} (the aggregate id) — same fix
 * applied when booking/identity/inventory/payment migrated to outbox; no consumer of this topic
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

    private void publish(Object event) {
        String topic = resolveTopic(event);
        if (topic == null) {
            log.warn("No topic for event: {}", event.getClass().getSimpleName());
            return;
        }
        String aggregateId = resolveAggregateId(event);
        EventEnvelope<?> envelope = EventEnvelope.of(topic, event, null);

        String payload = serialize(envelope);
        outboxEventJpaRepository.save(OutboxEventEntity.of(
                topic, aggregateId, event.getClass().getSimpleName(), payload, null));

        log.debug("Outbox row written: type={}, topic={}, aggregateId={}, eventId={}",
                event.getClass().getSimpleName(), topic, aggregateId, envelope.getEventId());
    }

    private String resolveTopic(Object event) {
        return switch (event) {
            case MatchPublishedEvent ignored -> KafkaTopics.MATCH_PUBLISHED;
            case ShowtimeAddedEvent ignored -> KafkaTopics.SHOWTIME_CREATED;
            default -> null;
        };
    }

    private String resolveAggregateId(Object event) {
        return switch (event) {
            case MatchPublishedEvent e -> e.matchId();
            case ShowtimeAddedEvent e -> e.showtimeId();
            default -> throw new IllegalArgumentException(
                    "No aggregate id mapping for domain event type: " + event.getClass().getSimpleName());
        };
    }

    private String serialize(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize domain event envelope", e);
        }
    }
}
