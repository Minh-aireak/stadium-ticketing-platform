package com.aireak.catalog.adapter.in.messaging;

import com.aireak.catalog.application.port.in.ApplySoldSeatsUseCase;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.domain.event.SeatsSoldEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SeatsSoldEventConsumerTest {

    @Test
    void decrementsAvailabilityByTheNumberOfActuallySoldSeats() {
        ApplySoldSeatsUseCase projection = mock(ApplySoldSeatsUseCase.class);
        SeatsSoldEventConsumer consumer = new SeatsSoldEventConsumer(projection);
        SeatsSoldEvent event = new SeatsSoldEvent("showtime-1", List.of("A1", "A2"), Instant.now());
        EventEnvelope<SeatsSoldEvent> envelope = EventEnvelope.of(
                "event-1", KafkaTopics.SEATS_SOLD, event, "trace-1");

        consumer.consume(envelope);

        verify(projection).applySoldSeats("event-1", "showtime-1", 2);
    }

    @Test
    void ignoresAnEmptySoldSeatEvent() {
        ApplySoldSeatsUseCase projection = mock(ApplySoldSeatsUseCase.class);
        SeatsSoldEventConsumer consumer = new SeatsSoldEventConsumer(projection);
        SeatsSoldEvent event = new SeatsSoldEvent("showtime-1", List.of(), Instant.now());

        consumer.consume(EventEnvelope.of("event-1", KafkaTopics.SEATS_SOLD, event, "trace-1"));

        verifyNoInteractions(projection);
    }
}
