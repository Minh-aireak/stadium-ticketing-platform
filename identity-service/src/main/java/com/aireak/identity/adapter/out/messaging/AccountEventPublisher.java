package com.aireak.identity.adapter.out.messaging;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.common.web.filter.CorrelationIdFilter;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbound Kafka adapter: publishes domain events wrapped in {@link EventEnvelope}.
 * Resolves topic name from {@link KafkaTopics} constants based on event type.
 *
 * <p>Hexagonal rule: this adapter is the ONLY place that knows about Kafka and topics.
 * Domain and application layers depend only on the {@link DomainEventPublisher} port.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventPublisher implements DomainEventPublisher {

    private final KafkaTemplate<String, EventEnvelope<?>> kafkaTemplate;

    @Override
    public void publish(Object domainEvent) {
        String topic = resolveTopic(domainEvent);
        if (topic == null) {
            log.warn("No topic mapping for domain event type: {}", domainEvent.getClass().getSimpleName());
            return;
        }
        String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
        EventEnvelope<?> envelope = EventEnvelope.of(domainEvent, traceId);
        kafkaTemplate.send(topic, envelope.getEventId(), envelope);
        log.debug("Published domain event: type={}, topic={}, eventId={}",
                domainEvent.getClass().getSimpleName(), topic, envelope.getEventId());
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
}
