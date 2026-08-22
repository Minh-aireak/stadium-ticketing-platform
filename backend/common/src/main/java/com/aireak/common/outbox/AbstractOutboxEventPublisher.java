package com.aireak.common.outbox;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.CorrelationIdRecordInterceptor;
import com.aireak.common.web.filter.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * The half of the transactional-outbox adapter that is the same in every service: wrap the event
 * in an {@link EventEnvelope}, stamp it with the current correlation id, serialize it and write
 * one {@code outbox_events} row inside the caller's transaction. Subclasses supply only the two
 * things that genuinely differ — which topic an event goes to, and which id is its aggregate.
 *
 * <p>This class exists because that "same in every service" used to mean five copies. They drifted:
 * two of them wrote {@code null} where the other three read the correlation id from the MDC, so
 * every event those two published arrived untraceable — and silently, because
 * {@link CorrelationIdRecordInterceptor} mints a fresh id for a null one, leaving consumer logs
 * looking correct while belonging to no request. With the mechanism in one place that can no
 * longer happen to one service and not the others.
 *
 * <p>Subclasses stay in their own service's {@code adapter.out.persistence.outbox} package and
 * implement that service's own {@code DomainEventPublisher} port, so the hexagonal rule holds:
 * topic names and the outbox table remain an adapter-layer concern.
 */
public abstract class AbstractOutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AbstractOutboxEventPublisher.class);

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final ObjectMapper objectMapper;

    protected AbstractOutboxEventPublisher(OutboxEventJpaRepository outboxEventJpaRepository,
                                            ObjectMapper objectMapper) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Topic for {@code event}, or {@code null} if this service does not publish that type — an
     * unmapped event is logged and dropped rather than failing the caller's transaction.
     */
    protected abstract String resolveTopic(Object event);

    /** Aggregate id for {@code event}, used as the Kafka message key. */
    protected abstract String resolveAggregateId(Object event);

    public void publishAll(List<Object> domainEvents) {
        domainEvents.forEach(this::publish);
    }

    public void publish(Object domainEvent) {
        String topic = resolveTopic(domainEvent);
        if (topic == null) {
            log.warn("No topic mapping for domain event type: {}", domainEvent.getClass().getSimpleName());
            return;
        }
        String aggregateId = resolveAggregateId(domainEvent);
        // On both the envelope (Debezium forwards the payload verbatim, so the consumer side can
        // restore it) and the row itself (so the table can be searched by trace when a message
        // never arrives). A @Scheduled reconciler has one too — see CorrelationIdSchedulingConfig.
        String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
        EventEnvelope<?> envelope = EventEnvelope.of(topic, domainEvent, traceId);

        String payload = serialize(envelope);
        outboxEventJpaRepository.save(OutboxEventEntity.of(
                topic, aggregateId, domainEvent.getClass().getSimpleName(), payload, traceId));

        log.debug("Outbox row written: type={}, topic={}, aggregateId={}, eventId={}",
                domainEvent.getClass().getSimpleName(), topic, aggregateId, envelope.getEventId());
    }

    private String serialize(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize domain event envelope", e);
        }
    }
}
