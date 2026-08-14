package com.aireak.inventory.adapter.in.messaging;

import com.aireak.catalog.domain.event.MatchCancelledEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.application.port.out.ShowtimeCatalogPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer adapter: marks a cancelled match's showtimes permanently unbookable in the
 * local cache {@link ShowtimeCatalogRestAdapter} keeps in front of catalog-service — see
 * {@link ShowtimeCatalogPort#markUnbookable} for why this is safe to cache (a cancellation is
 * never reversed).
 *
 * <p>Best-effort only: this is a performance/fail-fast optimization, not a correctness
 * dependency — {@code ShowtimeCatalogRestAdapter#requireBookable} still falls through to the
 * authoritative REST call to catalog-service whenever the local cache has nothing cached, so a
 * missed or delayed delivery here only costs a redundant network round trip, never an incorrect
 * booking.
 *
 * <p>Hexagonal rule: this adapter only translates Kafka message → port call. No business logic
 * here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchCancelledEventConsumer {

    private final ShowtimeCatalogPort showtimeCatalogPort;

    @KafkaListener(
            topics = KafkaTopics.MATCH_CANCELLED,
            groupId = "ticket-inventory-service-catalog",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received match-cancelled event: eventId={}, eventType={}",
                envelope.getEventId(), envelope.getEventType());

        if (!(envelope.getPayload() instanceof MatchCancelledEvent event)) {
            log.warn("Unexpected payload type on topic {}: {}", KafkaTopics.MATCH_CANCELLED,
                    envelope.getPayload() == null ? "null" : envelope.getPayload().getClass());
            return;
        }

        showtimeCatalogPort.markUnbookable(event.showtimeIds());
        log.info("Marked showtimes unbookable after match cancellation: matchId={}, showtimeIds={}",
                event.matchId(), event.showtimeIds());
    }
}
