package com.aireak.catalog.adapter.in.messaging;

import com.aireak.catalog.application.port.in.ApplySoldSeatsUseCase;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatsSoldEventConsumer {

    private final ApplySoldSeatsUseCase applySoldSeatsUseCase;

    @KafkaListener(
            topics = KafkaTopics.SEATS_SOLD,
            groupId = "match-catalog-service-inventory",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        if (!(envelope.getPayload() instanceof SeatsSoldEvent event)) {
            log.warn("Unexpected payload on {}: {}", KafkaTopics.SEATS_SOLD,
                    envelope.getPayload() == null ? "null" : envelope.getPayload().getClass());
            return;
        }
        int soldSeatCount = event.seatCodes() == null ? 0 : event.seatCodes().size();
        if (soldSeatCount == 0) {
            log.warn("Ignoring empty sold-seat event: eventId={}, showtime={}",
                    envelope.getEventId(), event.showtimeId());
            return;
        }
        // Outcome is logged by the use case itself, which knows what actually happened to each of
        // the two stores — repeating it here only produced a second, less informative line.
        applySoldSeatsUseCase.applySoldSeats(envelope.getEventId(), event.showtimeId(), soldSeatCount);
    }
}
