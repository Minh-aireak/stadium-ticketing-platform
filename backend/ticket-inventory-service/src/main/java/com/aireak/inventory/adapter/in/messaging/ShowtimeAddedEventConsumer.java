package com.aireak.inventory.adapter.in.messaging;

import com.aireak.catalog.domain.event.ShowtimeAddedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.application.port.in.GenerateSeatMapUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer adapter: generates the seat map when catalog-service adds a showtime.
 *
 * <p>Idempotency (redelivery/replay) is handled by the application service, not here — see
 * {@code SeatMapGenerationService#generate}.
 *
 * <p>Hexagonal rule: this adapter only translates Kafka message → use case call. No business
 * logic here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShowtimeAddedEventConsumer {

    private final GenerateSeatMapUseCase generateSeatMapUseCase;

    @KafkaListener(
            topics = KafkaTopics.SHOWTIME_CREATED,
            groupId = "ticket-inventory-service-catalog",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received showtime event: eventId={}, eventType={}",
                envelope.getEventId(), envelope.getEventType());

        if (!(envelope.getPayload() instanceof ShowtimeAddedEvent event)) {
            log.warn("Unexpected payload type on topic {}: {}", KafkaTopics.SHOWTIME_CREATED,
                    envelope.getPayload() == null ? "null" : envelope.getPayload().getClass());
            return;
        }

        generateSeatMapUseCase.generate(event.showtimeId(), event.stadiumId(), event.totalSeats(), event.basePrice());
    }
}
