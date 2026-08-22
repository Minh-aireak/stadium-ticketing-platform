package com.aireak.catalog.adapter.out.persistence.outbox;

import com.aireak.catalog.application.port.out.DomainEventPublisher;
import com.aireak.catalog.domain.event.MatchCancelledEvent;
import com.aireak.catalog.domain.event.MatchCompletedEvent;
import com.aireak.catalog.domain.event.MatchPublishedEvent;
import com.aireak.catalog.domain.event.ShowtimeAddedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.common.outbox.AbstractOutboxEventPublisher;
import com.aireak.common.outbox.OutboxEventJpaRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

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
 * to Kafka, so consumers see an unchanged message shape. The Kafka message key is {@code matchId}
 * (the aggregate id).
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
            case MatchPublishedEvent ignored -> KafkaTopics.MATCH_PUBLISHED;
            case MatchCancelledEvent ignored -> KafkaTopics.MATCH_CANCELLED;
            case MatchCompletedEvent ignored -> KafkaTopics.MATCH_COMPLETED;
            case ShowtimeAddedEvent ignored -> KafkaTopics.SHOWTIME_CREATED;
            default -> null;
        };
    }

    @Override
    protected String resolveAggregateId(Object event) {
        return switch (event) {
            case MatchPublishedEvent e -> e.matchId();
            case MatchCancelledEvent e -> e.matchId();
            case MatchCompletedEvent e -> e.matchId();
            case ShowtimeAddedEvent e -> e.showtimeId();
            default -> throw new IllegalArgumentException(
                    "No aggregate id mapping for domain event type: " + event.getClass().getSimpleName());
        };
    }
}
