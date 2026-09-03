package com.aireak.booking.adapter.in.messaging;

import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.catalog.domain.event.MatchCancelledEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: consumes {@code MatchCancelledEvent} from match-catalog-service and
 * cancels every active booking for the match's showtimes, refunding the ones already paid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchCancelledConsumer {

    /**
     * Named rather than inlined because {@link MatchCancelledDeadLetterConsumer} has to recognise
     * this group on a dead-lettered record: {@code catalog.match.cancelled} is consumed by
     * ticket-inventory-service too and both services share the one {@code -dlt} topic.
     */
    public static final String GROUP_ID = "booking-service-match-cancelled";

    private final BookingOrchestrationService bookingOrchestrationService;

    @KafkaListener(
            topics = KafkaTopics.MATCH_CANCELLED,
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received match event: type={}, eventId={}", envelope.getEventType(), envelope.getEventId());

        if (!(envelope.getPayload() instanceof MatchCancelledEvent event)) {
            log.warn("Unknown match event payload for eventType={}", envelope.getEventType());
            return;
        }

        log.info("Match cancelled: matchId={}, showtimes={}, reason={}",
                event.matchId(), event.showtimeIds().size(), event.reason());
        for (String showtimeId : event.showtimeIds()) {
            bookingOrchestrationService.cancelBookingsForShowtime(showtimeId, event.reason());
        }
    }
}
