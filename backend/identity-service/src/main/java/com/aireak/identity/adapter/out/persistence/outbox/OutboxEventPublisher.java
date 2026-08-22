package com.aireak.identity.adapter.out.persistence.outbox;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.common.outbox.AbstractOutboxEventPublisher;
import com.aireak.common.outbox.OutboxEventJpaRepository;
import com.aireak.identity.application.port.out.DomainEventPublisher;
import com.aireak.identity.domain.event.AccountActivatedEvent;
import com.aireak.identity.domain.event.AccountRegisteredEvent;
import com.aireak.identity.domain.event.PasswordResetRequestedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Outbox adapter implementing {@link DomainEventPublisher}: instead of talking to Kafka directly,
 * it writes a row to {@code outbox_events} in the SAME transaction as the Account aggregate
 * save — Debezium CDC tails that table and produces to Kafka, see
 * {@code infra/debezium/identity-outbox-connector.json}.
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
            case AccountRegisteredEvent ignored -> KafkaTopics.ACCOUNT_REGISTERED;
            case AccountActivatedEvent ignored -> KafkaTopics.ACCOUNT_ACTIVATED;
            case PasswordResetRequestedEvent ignored -> KafkaTopics.PASSWORD_RESET_REQUESTED;
            default -> null;
        };
    }

    @Override
    protected String resolveAggregateId(Object event) {
        return switch (event) {
            case AccountRegisteredEvent e -> e.accountId().toString();
            case AccountActivatedEvent e -> e.accountId().toString();
            case PasswordResetRequestedEvent e -> e.accountId().toString();
            default -> throw new IllegalArgumentException(
                    "No aggregate id mapping for domain event type: " + event.getClass().getSimpleName());
        };
    }
}
