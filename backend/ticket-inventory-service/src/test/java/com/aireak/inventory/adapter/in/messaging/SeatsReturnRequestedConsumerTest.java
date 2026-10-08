package com.aireak.inventory.adapter.in.messaging;

import com.aireak.booking.domain.event.SeatsReturnRequestedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.application.port.in.ReturnSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ReturnSeatsCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SeatsReturnRequestedConsumerTest {

    @Mock
    private ReturnSeatsUseCase returnSeatsUseCase;

    private static EventEnvelope<?> envelope(Object payload) {
        return EventEnvelope.of(KafkaTopics.SEATS_RETURN_REQUESTED, payload, "trace-1");
    }

    @Test
    void handsTheCancelledSeatsToTheUseCase() {
        new SeatsReturnRequestedConsumer(returnSeatsUseCase).consume(envelope(new SeatsReturnRequestedEvent(
                "booking-1", "showtime-1", List.of("A1", "A2"), "Cancelled by the customer", Instant.now())));

        verify(returnSeatsUseCase).execute(new ReturnSeatsCommand("showtime-1", "booking-1", List.of("A1", "A2")));
    }

    /** Swallowing would leave cancelled seats unsellable with no trace; the error handler must see it. */
    @Test
    void letsAFailurePropagateToTheErrorHandler() {
        doThrow(new IllegalStateException("Could not acquire distributed lock"))
                .when(returnSeatsUseCase).execute(any());

        assertThatThrownBy(() -> new SeatsReturnRequestedConsumer(returnSeatsUseCase).consume(envelope(
                new SeatsReturnRequestedEvent("booking-1", "showtime-1", List.of("A1"), "r", Instant.now()))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ignoresAnEnvelopeCarryingSomeOtherPayload() {
        new SeatsReturnRequestedConsumer(returnSeatsUseCase).consume(envelope("not an event"));

        verify(returnSeatsUseCase, never()).execute(any());
    }
}
