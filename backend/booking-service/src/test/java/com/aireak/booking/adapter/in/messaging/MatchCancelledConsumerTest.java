package com.aireak.booking.adapter.in.messaging;

import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.catalog.domain.event.MatchCancelledEvent;
import com.aireak.common.event.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.aireak.common.kafka.KafkaTopics.MATCH_CANCELLED;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Exercises the saga-driving Kafka consumer with the same {@link EventEnvelope} shape
 * match-catalog-service's {@code OutboxEventPublisher} actually produces — see
 * {@link MatchCancelledEvent}'s javadoc for why this is booking-service's own local copy of that
 * event, not a shared module dependency.
 */
@ExtendWith(MockitoExtension.class)
class MatchCancelledConsumerTest {

    @Mock
    private BookingOrchestrationService bookingOrchestrationService;

    private MatchCancelledConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MatchCancelledConsumer(bookingOrchestrationService);
    }

    @Test
    void consume_matchCancelledAsProducedInProduction_cancelsBookingsForEveryShowtime() {
        MatchCancelledEvent cancelled = new MatchCancelledEvent(
                "match-1", "Home FC", "Away FC", List.of("showtime-1", "showtime-2"),
                "Stadium closed for safety inspection", Instant.now());
        EventEnvelope<MatchCancelledEvent> envelope = EventEnvelope.of(MATCH_CANCELLED, cancelled, null);

        consumer.consume(envelope);

        verify(bookingOrchestrationService).cancelBookingsForShowtime(
                "showtime-1", "Stadium closed for safety inspection");
        verify(bookingOrchestrationService).cancelBookingsForShowtime(
                "showtime-2", "Stadium closed for safety inspection");
    }

    @Test
    void consume_unrecognizedPayload_doesNothing() {
        EventEnvelope<String> envelope = EventEnvelope.of(MATCH_CANCELLED, "not-a-match-event", null);

        consumer.consume(envelope);

        verifyNoInteractions(bookingOrchestrationService);
    }
}
