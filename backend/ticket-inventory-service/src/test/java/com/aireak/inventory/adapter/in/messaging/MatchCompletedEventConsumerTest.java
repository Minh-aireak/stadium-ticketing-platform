package com.aireak.inventory.adapter.in.messaging;

import com.aireak.catalog.domain.event.MatchCompletedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.inventory.application.port.out.ShowtimeCatalogPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MatchCompletedEventConsumerTest {

    @Test
    void marksEveryListedShowtimeUnbookable() {
        ShowtimeCatalogPort showtimeCatalogPort = mock(ShowtimeCatalogPort.class);
        MatchCompletedEventConsumer consumer = new MatchCompletedEventConsumer(showtimeCatalogPort);
        MatchCompletedEvent event = new MatchCompletedEvent("match-1", "Home FC", "Away FC",
                List.of("showtime-1", "showtime-2"), Instant.now());
        EventEnvelope<MatchCompletedEvent> envelope = EventEnvelope.of(
                "event-1", KafkaTopics.MATCH_COMPLETED, event, "trace-1");

        consumer.consume(envelope);

        verify(showtimeCatalogPort).markUnbookable(List.of("showtime-1", "showtime-2"));
    }

    @Test
    void ignoresAnUnexpectedPayloadType() {
        ShowtimeCatalogPort showtimeCatalogPort = mock(ShowtimeCatalogPort.class);
        MatchCompletedEventConsumer consumer = new MatchCompletedEventConsumer(showtimeCatalogPort);
        EventEnvelope<String> envelope = EventEnvelope.of(
                "event-1", KafkaTopics.MATCH_COMPLETED, "not-a-match-completed-event", "trace-1");

        consumer.consume(envelope);

        verifyNoInteractions(showtimeCatalogPort);
    }
}
