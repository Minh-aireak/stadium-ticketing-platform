package com.aireak.booking.adapter.out.persistence.outbox;

import com.aireak.booking.application.port.out.DomainEventPublisher;
import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.booking.domain.event.BookingCreatedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.common.web.filter.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;

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
        EventEnvelope<?> envelope = EventEnvelope.of(topic, domainEvent, traceId);

        String payload = serialize(envelope);
        outboxEventJpaRepository.save(OutboxEventEntity.of(
                topic, aggregateId, domainEvent.getClass().getSimpleName(), payload, traceId));

        log.debug("Outbox row written: type={}, topic={}, aggregateId={}, eventId={}",
                domainEvent.getClass().getSimpleName(), topic, aggregateId, envelope.getEventId());
    }

    private String resolveTopic(Object event) {
        return switch (event) {
            case BookingCreatedEvent ignored -> KafkaTopics.BOOKING_CREATED;
            case BookingConfirmedEvent ignored -> KafkaTopics.BOOKING_CONFIRMED;
            case BookingCancelledEvent ignored -> KafkaTopics.BOOKING_CANCELLED;
            default -> null;
        };
    }

    private String resolveAggregateId(Object event) {
        return switch (event) {
            case BookingCreatedEvent e -> e.bookingId();
            case BookingConfirmedEvent e -> e.bookingId();
            case BookingCancelledEvent e -> e.bookingId();
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
