package com.aireak.payment.adapter.out.persistence.outbox;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.common.outbox.AbstractOutboxEventPublisher;
import com.aireak.common.outbox.OutboxEventJpaRepository;
import com.aireak.payment.application.port.out.DomainEventPublisher;
import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentInitiatedEvent;
import com.aireak.payment.domain.event.PaymentRefundedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Outbox adapter implementing {@link DomainEventPublisher}: instead of talking to Kafka directly,
 * it writes a row to {@code outbox_events} in the SAME transaction as the Payment aggregate
 * save — Debezium CDC tails that table and produces to Kafka, see
 * {@code infra/debezium/payment-outbox-connector.json}.
 *
 * <p>The written payload is the same {@link EventEnvelope} JSON that used to be sent directly to
 * Kafka, so consumers see an unchanged message shape.
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
            case PaymentInitiatedEvent ignored -> KafkaTopics.PAYMENT_INITIATED;
            case PaymentSucceededEvent ignored -> KafkaTopics.PAYMENT_SUCCEEDED;
            case PaymentFailedEvent ignored -> KafkaTopics.PAYMENT_FAILED;
            case PaymentRefundedEvent ignored -> KafkaTopics.PAYMENT_REFUNDED;
            default -> null;
        };
    }

    @Override
    protected String resolveAggregateId(Object event) {
        return switch (event) {
            case PaymentInitiatedEvent e -> e.paymentId();
            case PaymentSucceededEvent e -> e.paymentId();
            case PaymentFailedEvent e -> e.paymentId();
            case PaymentRefundedEvent e -> e.paymentId();
            default -> throw new IllegalArgumentException(
                    "No aggregate id mapping for domain event type: " + event.getClass().getSimpleName());
        };
    }
}
