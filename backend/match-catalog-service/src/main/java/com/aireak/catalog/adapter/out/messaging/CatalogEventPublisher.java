package com.aireak.catalog.adapter.out.messaging;

import com.aireak.catalog.application.port.out.DomainEventPublisher;
import com.aireak.catalog.domain.event.MatchPublishedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogEventPublisher implements DomainEventPublisher {

    private final KafkaTemplate<String, EventEnvelope<?>> kafkaTemplate;

    @Override
    public void publishAll(List<Object> events) {
        events.forEach(this::publish);
    }

    private void publish(Object event) {
        String topic = switch (event) {
            case MatchPublishedEvent ignored -> KafkaTopics.MATCH_PUBLISHED;
            default -> null;
        };
        if (topic == null) return;
        EventEnvelope<?> envelope = EventEnvelope.of(event, null);
        kafkaTemplate.send(topic, envelope.getEventId(), envelope);
        log.debug("Published: type={}, topic={}", event.getClass().getSimpleName(), topic);
    }
}
