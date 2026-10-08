package com.aireak.catalog.adapter.in.messaging;

import com.aireak.catalog.application.port.in.ApplyReturnedSeatsUseCase;
import com.aireak.catalog.application.port.in.ApplySoldSeatsUseCase;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.domain.event.SeatsReturnedEvent;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Projects ticket-inventory-service's seat movements onto the catalog's availability numbers:
 * seats sold, and seats a cancelled paid booking put back on sale. Both arrive on this one topic,
 * keyed by showtimeId (see {@code KafkaTopics#SEATS_RETURNED}), so one listener applies a showtime's
 * sales and returns strictly one at a time — which both projections' reseed paths depend on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatsSoldEventConsumer {

    private final ApplySoldSeatsUseCase applySoldSeatsUseCase;
    private final ApplyReturnedSeatsUseCase applyReturnedSeatsUseCase;

    @KafkaListener(
            topics = KafkaTopics.SEATS_SOLD,
            groupId = "match-catalog-service-inventory",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        switch (envelope.getPayload()) {
            case SeatsSoldEvent event -> applySold(envelope.getEventId(), event);
            case SeatsReturnedEvent event -> applyReturned(envelope.getEventId(), event);
            case null, default -> log.warn("Unexpected payload on {}: {}", KafkaTopics.SEATS_SOLD,
                    envelope.getPayload() == null ? "null" : envelope.getPayload().getClass());
        }
    }

    private void applySold(String eventId, SeatsSoldEvent event) {
        int soldSeatCount = count(event.seatCodes());
        if (soldSeatCount == 0) {
            log.warn("Ignoring empty sold-seat event: eventId={}, showtime={}", eventId, event.showtimeId());
            return;
        }
        // Outcome is logged by the use case itself, which knows what actually happened to each of
        // the two stores — repeating it here only produced a second, less informative line.
        applySoldSeatsUseCase.applySoldSeats(eventId, event.showtimeId(), soldSeatCount);
    }

    private void applyReturned(String eventId, SeatsReturnedEvent event) {
        int returnedSeatCount = count(event.seatCodes());
        if (returnedSeatCount == 0) {
            log.warn("Ignoring empty returned-seat event: eventId={}, showtime={}, booking={}",
                    eventId, event.showtimeId(), event.bookingId());
            return;
        }
        log.info("Seats returned to sale by a cancelled booking: eventId={}, showtime={}, booking={}, seats={}",
                eventId, event.showtimeId(), event.bookingId(), returnedSeatCount);
        applyReturnedSeatsUseCase.applyReturnedSeats(eventId, event.showtimeId(), returnedSeatCount);
    }

    private static int count(List<?> seatCodes) {
        return seatCodes == null ? 0 : seatCodes.size();
    }
}
