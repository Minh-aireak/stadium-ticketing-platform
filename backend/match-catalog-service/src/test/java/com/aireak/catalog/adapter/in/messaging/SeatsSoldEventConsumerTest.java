package com.aireak.catalog.adapter.in.messaging;

import com.aireak.catalog.application.port.in.ApplyReturnedSeatsUseCase;
import com.aireak.catalog.application.port.in.ApplySoldSeatsUseCase;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.domain.event.SeatsReturnedEvent;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SeatsSoldEventConsumerTest {

    private final ApplySoldSeatsUseCase soldProjection = mock(ApplySoldSeatsUseCase.class);
    private final ApplyReturnedSeatsUseCase returnedProjection = mock(ApplyReturnedSeatsUseCase.class);
    private final SeatsSoldEventConsumer consumer = new SeatsSoldEventConsumer(soldProjection, returnedProjection);

    @Test
    void decrementsAvailabilityByTheNumberOfActuallySoldSeats() {
        SeatsSoldEvent event = new SeatsSoldEvent("showtime-1", List.of("A1", "A2"), Instant.now());
        EventEnvelope<SeatsSoldEvent> envelope = EventEnvelope.of(
                "event-1", KafkaTopics.SEATS_SOLD, event, "trace-1");

        consumer.consume(envelope);

        verify(soldProjection).applySoldSeats("event-1", "showtime-1", 2);
        verifyNoInteractions(returnedProjection);
    }

    @Test
    void ignoresAnEmptySoldSeatEvent() {
        SeatsSoldEvent event = new SeatsSoldEvent("showtime-1", List.of(), Instant.now());

        consumer.consume(EventEnvelope.of("event-1", KafkaTopics.SEATS_SOLD, event, "trace-1"));

        verifyNoInteractions(soldProjection, returnedProjection);
    }

    @Test
    void addsBackTheNumberOfSeatsACancelledBookingReturned() {
        SeatsReturnedEvent event = new SeatsReturnedEvent("showtime-1", "booking-1", List.of("A2"), Instant.now());

        consumer.consume(EventEnvelope.of("event-2", KafkaTopics.SEATS_RETURNED, event, "trace-2"));

        verify(returnedProjection).applyReturnedSeats("event-2", "showtime-1", 1);
        verifyNoInteractions(soldProjection);
    }

    @Test
    void ignoresAPayloadThatIsNeitherKind() {
        consumer.consume(EventEnvelope.of("event-3", KafkaTopics.SEATS_SOLD, "not an event", "trace-3"));

        verifyNoInteractions(soldProjection, returnedProjection);
    }
}
