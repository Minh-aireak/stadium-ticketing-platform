package com.aireak.payment.adapter.out.persistence.outbox;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.payment.application.port.out.DomainEventPublisher;
import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentInitiatedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbox adapter implementing {@link DomainEventPublisher}: instead of talking to Kafka
 * directly, it writes a row to {@code outbox_events} in the SAME transaction as the Payment
 * aggregate save — Debezium CDC tails that table and produces to Kafka, see
 * {@code infra/debezium/payment-outbox-connector.json}.
 *
 * <p>Replaces the old {@code PaymentEventPublisher}, which called {@code KafkaTemplate.send()}
 * directly inside {@code PaymentSagaSteps}' {@code REQUIRES_NEW} transactions — a crash or
 * broker hiccup between the DB commit and the Kafka send could silently drop
 * {@code PaymentSucceededEvent}/{@code PaymentFailedEvent}, leaving booking-service's saga
 * waiting forever. Writing to this table instead keeps the event write inside the same
 * transaction as the Payment status change, so the two can never disagree.
 *
 * <p>The written payload is the same {@link EventEnvelope} JSON that used to be sent directly
 * to Kafka, so consumers (booking-service's {@code PaymentResultConsumer}) see an unchanged
 * message shape. The Kafka message key changes from the old (effectively random) {@code eventId}
 * to {@code paymentId} (the aggregate id) — same fix applied when booking/identity/inventory
 * migrated to outbox; no consumer reads the Kafka record key today, so this is safe.
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
            case PaymentInitiatedEvent ignored -> KafkaTopics.PAYMENT_INITIATED;
            case PaymentSucceededEvent ignored -> KafkaTopics.PAYMENT_SUCCEEDED;
            case PaymentFailedEvent ignored   -> KafkaTopics.PAYMENT_FAILED;
            default -> null;
        };
    }

    private String resolveAggregateId(Object event) {
        return switch (event) {
            case PaymentInitiatedEvent e -> e.paymentId();
            case PaymentSucceededEvent e -> e.paymentId();
            case PaymentFailedEvent e    -> e.paymentId();
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
