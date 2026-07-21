package com.aireak.identity.adapter.out.persistence.outbox;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.common.web.filter.CorrelationIdFilter;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbox adapter implementing {@link DomainEventPublisher}: instead of talking to
 * Kafka directly, it writes a row to {@code outbox_events} in the SAME transaction
 * as the aggregate save. Debezium CDC tails that table and produces to Kafka —
 * see {@code infra/debezium/identity-outbox-connector.json}.
 *
 * <p>The written payload is the same {@link EventEnvelope} JSON that used to be
 * sent directly to Kafka, so consumers (e.g. notification-service) see an
 * unchanged message shape.
 *
 * <p>Hexagonal rule: this adapter is the ONLY place that knows about the outbox
 * table and topic names. Domain and application layers depend only on the
 * {@link DomainEventPublisher} port.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher implements DomainEventPublisher {

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(Object domainEvent) {
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

    @Override
    public void publishAll(List<Object> domainEvents) {
        domainEvents.forEach(this::publish);
    }

    private String resolveTopic(Object event) {
        return switch (event) {
            case AccountRegisteredEvent ignored -> KafkaTopics.ACCOUNT_REGISTERED;
            case AccountActivatedEvent ignored  -> KafkaTopics.ACCOUNT_ACTIVATED;
            default -> null;
        };
    }

    private String resolveAggregateId(Object event) {
        return switch (event) {
            case AccountRegisteredEvent e -> e.accountId().toString();
            case AccountActivatedEvent e  -> e.accountId().toString();
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
