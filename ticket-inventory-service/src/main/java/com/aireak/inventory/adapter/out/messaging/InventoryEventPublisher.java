package com.aireak.inventory.adapter.out.messaging;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.domain.event.SeatsReleasedEvent;
import com.aireak.inventory.domain.event.SeatsReservedEvent;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventPublisher implements DomainEventPublisher {

    private final KafkaTemplate<String, EventEnvelope<?>> kafkaTemplate;

    @Override
    public void publishAll(List<Object> events) {
        events.forEach(this::publish);
    }

    private void publish(Object event) {
        String topic = switch (event) {
            case SeatsReservedEvent ignored -> KafkaTopics.SEATS_RESERVED;
            case SeatsReleasedEvent ignored -> KafkaTopics.SEATS_RELEASED;
            case SeatsSoldEvent ignored     -> KafkaTopics.SEATS_SOLD;
            default -> null;
        };
        if (topic == null) return;
        EventEnvelope<?> envelope = EventEnvelope.of(event, null);
        kafkaTemplate.send(topic, envelope.getEventId(), envelope);
        log.debug("Published: type={}, topic={}", event.getClass().getSimpleName(), topic);
    }
}
