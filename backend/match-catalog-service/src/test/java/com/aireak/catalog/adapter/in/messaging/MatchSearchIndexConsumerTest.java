package com.aireak.catalog.adapter.in.messaging;

import com.aireak.catalog.application.port.in.ReconcileMatchSearchIndexUseCase;
import com.aireak.catalog.domain.event.MatchCancelledEvent;
import com.aireak.catalog.domain.event.MatchCompletedEvent;
import com.aireak.catalog.domain.event.MatchPublishedEvent;
import com.aireak.catalog.domain.event.ShowtimeAddedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MatchSearchIndexConsumerTest {

    private final ReconcileMatchSearchIndexUseCase reconcile = mock(ReconcileMatchSearchIndexUseCase.class);
    private final MatchSearchIndexConsumer consumer = new MatchSearchIndexConsumer(reconcile);

    @Test
    void reconcilesTheMatchAPublishedEventNames() {
        consumer.consume(EventEnvelope.of("event-1", KafkaTopics.MATCH_PUBLISHED,
                new MatchPublishedEvent("match-1", "Home FC", "Away FC", "Premier League"), "trace-1"));

        verify(reconcile).reconcile("match-1");
    }

    @Test
    void reconcilesTheMatchACancelledEventNames() {
        consumer.consume(EventEnvelope.of("event-2", KafkaTopics.MATCH_CANCELLED,
                new MatchCancelledEvent("match-2", "Home FC", "Away FC", List.of("showtime-1"), "Rain"),
                "trace-2"));

        verify(reconcile).reconcile("match-2");
    }

    @Test
    void reconcilesTheMatchACompletedEventNames() {
        consumer.consume(EventEnvelope.of("event-3", KafkaTopics.MATCH_COMPLETED,
                new MatchCompletedEvent("match-3", "Home FC", "Away FC", List.of("showtime-1")), "trace-3"));

        verify(reconcile).reconcile("match-3");
    }

    /**
     * Only the id crosses from the event to the reconciler — the event's teams and competition
     * are not what gets indexed, the database row is. So there is nothing here to assert about
     * the payload beyond its id, and that is deliberate.
     */
    @Test
    void ignoresAPayloadThatIsNotAMatchLifecycleEvent() {
        ShowtimeAddedEvent unrelated = new ShowtimeAddedEvent("match-1", "showtime-1", "my-dinh",
                Instant.now(), 100, BigDecimal.TEN, "VND");

        assertThatCode(() -> consumer.consume(EventEnvelope.of("event-4", KafkaTopics.SHOWTIME_CREATED,
                unrelated, "trace-4"))).doesNotThrowAnyException();

        verifyNoInteractions(reconcile);
    }

    @Test
    void ignoresANullPayloadWithoutThrowing() {
        assertThatCode(() -> consumer.consume(EventEnvelope.of("event-5", KafkaTopics.MATCH_PUBLISHED,
                null, "trace-5"))).doesNotThrowAnyException();

        verifyNoInteractions(reconcile);
    }
}
