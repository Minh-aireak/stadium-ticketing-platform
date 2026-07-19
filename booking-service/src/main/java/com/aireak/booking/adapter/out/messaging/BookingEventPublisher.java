package com.aireak.booking.adapter.out.messaging;

import com.aireak.booking.application.port.out.DomainEventPublisher;
import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.booking.domain.event.BookingCreatedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbound Kafka adapter: publishes booking domain events.
 * notification-service listens to BOOKING_CONFIRMED/CANCELLED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventPublisher implements DomainEventPublisher {

    private final KafkaTemplate<String, EventEnvelope<?>> kafkaTemplate;

    @Override
    public void publishAll(List<Object> events) {
        events.forEach(this::publish);
    }

    private void publish(Object event) {
        String topic = resolveTopic(event);
        if (topic == null) return;
        EventEnvelope<?> envelope = EventEnvelope.of(event, null);
        kafkaTemplate.send(topic, envelope.getEventId(), envelope);
        log.debug("Published: type={}, topic={}", event.getClass().getSimpleName(), topic);
    }

    private String resolveTopic(Object event) {
        return switch (event) {
            case BookingCreatedEvent ignored   -> KafkaTopics.BOOKING_CREATED;
            case BookingConfirmedEvent ignored -> KafkaTopics.BOOKING_CONFIRMED;
            case BookingCancelledEvent ignored -> KafkaTopics.BOOKING_CANCELLED;
            default -> null;
        };
    }
}
