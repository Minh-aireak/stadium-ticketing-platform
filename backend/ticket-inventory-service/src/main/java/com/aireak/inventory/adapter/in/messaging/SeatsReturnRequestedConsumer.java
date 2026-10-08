package com.aireak.inventory.adapter.in.messaging;

import com.aireak.booking.domain.event.SeatsReturnRequestedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.application.port.in.ReturnSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ReturnSeatsCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer adapter: seats a customer cancelled in booking-service, to be given back. Written
 * to the outbox in the transaction that cancelled them, so this runs even if this service was down
 * at that moment.
 *
 * <p>Idempotent by construction (see {@code SeatReturnService}), so a redelivery is harmless.
 * Anything thrown here is left to {@code kafkaErrorHandler}: retried with backoff, then
 * dead-lettered to {@link SeatsReturnRequestedDeadLetterConsumer}.
 *
 * <p>Hexagonal rule: this adapter only translates Kafka message → use case call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatsReturnRequestedConsumer {

    public static final String GROUP_ID = "ticket-inventory-service-booking";

    private final ReturnSeatsUseCase returnSeatsUseCase;

    @KafkaListener(
            topics = KafkaTopics.SEATS_RETURN_REQUESTED,
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        if (!(envelope.getPayload() instanceof SeatsReturnRequestedEvent event)) {
            log.warn("Unexpected payload type on topic {}: {}", KafkaTopics.SEATS_RETURN_REQUESTED,
                    envelope.getPayload() == null ? "null" : envelope.getPayload().getClass());
            return;
        }
        log.info("Seat return requested: eventId={}, booking={}, showtime={}, seats={}, reason={}",
                envelope.getEventId(), event.bookingId(), event.showtimeId(), event.seatCodes(), event.reason());
        returnSeatsUseCase.execute(new ReturnSeatsCommand(event.showtimeId(), event.bookingId(), event.seatCodes()));
    }
}
