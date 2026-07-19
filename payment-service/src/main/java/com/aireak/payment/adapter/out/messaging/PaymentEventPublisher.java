package com.aireak.payment.adapter.out.messaging;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.payment.application.port.out.DomainEventPublisher;
import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentInitiatedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbound Kafka adapter: publishes payment domain events.
 * booking-service listens to PAYMENT_SUCCEEDED/FAILED to drive saga.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher implements DomainEventPublisher {

    private final KafkaTemplate<String, EventEnvelope<?>> kafkaTemplate;

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
        EventEnvelope<?> envelope = EventEnvelope.of(event, null);
        kafkaTemplate.send(topic, envelope.getEventId(), envelope);
        log.debug("Published: type={}, topic={}", event.getClass().getSimpleName(), topic);
    }

    private String resolveTopic(Object event) {
        return switch (event) {
            case PaymentInitiatedEvent ignored -> KafkaTopics.PAYMENT_INITIATED;
            case PaymentSucceededEvent ignored -> KafkaTopics.PAYMENT_SUCCEEDED;
            case PaymentFailedEvent ignored   -> KafkaTopics.PAYMENT_FAILED;
            default -> null;
        };
    }
}
