package com.aireak.booking.adapter.out.persistence.outbox;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.common.outbox.AbstractOutboxEventPublisher;
import com.aireak.common.outbox.OutboxEventJpaRepository;
import com.aireak.booking.application.port.out.DomainEventPublisher;
import com.aireak.booking.domain.event.BookingCancelledEvent;
import com.aireak.booking.domain.event.BookingConfirmedEvent;
import com.aireak.booking.domain.event.BookingCreatedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Outbox adapter implementing {@link DomainEventPublisher}: instead of talking to Kafka directly,
 * it writes a row to {@code outbox_events} in the SAME transaction as the Booking aggregate
 * save — Debezium CDC tails that table and produces to Kafka, see
 * {@code infra/debezium/booking-outbox-connector.json}.
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
            case BookingCreatedEvent ignored -> KafkaTopics.BOOKING_CREATED;
            case BookingConfirmedEvent ignored -> KafkaTopics.BOOKING_CONFIRMED;
            case BookingCancelledEvent ignored -> KafkaTopics.BOOKING_CANCELLED;
            default -> null;
        };
    }

    @Override
    protected String resolveAggregateId(Object event) {
        return switch (event) {
            case BookingCreatedEvent e -> e.bookingId();
            case BookingConfirmedEvent e -> e.bookingId();
            case BookingCancelledEvent e -> e.bookingId();
            default -> throw new IllegalArgumentException(
                    "No aggregate id mapping for domain event type: " + event.getClass().getSimpleName());
        };
    }
}
