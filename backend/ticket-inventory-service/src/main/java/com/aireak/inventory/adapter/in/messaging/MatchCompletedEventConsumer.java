package com.aireak.inventory.adapter.in.messaging;

import com.aireak.catalog.domain.event.MatchCompletedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.application.port.out.ShowtimeCatalogPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer adapter: marks a completed match's showtimes permanently unbookable in the
 * local cache {@link ShowtimeCatalogRestAdapter} keeps in front of catalog-service. See
 * {@link MatchCancelledEventConsumer} — same rationale, mirrored for the COMPLETED transition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchCompletedEventConsumer {

    private final ShowtimeCatalogPort showtimeCatalogPort;

    @KafkaListener(
            topics = KafkaTopics.MATCH_COMPLETED,
            groupId = "ticket-inventory-service-catalog",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received match-completed event: eventId={}, eventType={}",
                envelope.getEventId(), envelope.getEventType());

        if (!(envelope.getPayload() instanceof MatchCompletedEvent event)) {
            log.warn("Unexpected payload type on topic {}: {}", KafkaTopics.MATCH_COMPLETED,
                    envelope.getPayload() == null ? "null" : envelope.getPayload().getClass());
            return;
        }

        showtimeCatalogPort.markUnbookable(event.showtimeIds());
        log.info("Marked showtimes unbookable after match completion: matchId={}, showtimeIds={}",
                event.matchId(), event.showtimeIds());
    }
}
