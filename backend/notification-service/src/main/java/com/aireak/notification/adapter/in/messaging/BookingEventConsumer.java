package com.aireak.notification.adapter.in.messaging;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.notification.application.port.in.SendNotificationUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer adapter for booking-related domain events.
 * Idempotency is handled by the application service, not here.
 *
 * <p>Hexagonal rule: this adapter only translates Kafka message → use case call.
 * No business logic here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventConsumer {

    /** Named rather than inlined so {@link NotificationDeadLetterConsumer} can recognise it. */
    public static final String GROUP_ID = "notification-service-booking";

    private final SendNotificationUseCase sendNotificationUseCase;

    @KafkaListener(
            topics = {KafkaTopics.BOOKING_CREATED, KafkaTopics.BOOKING_CONFIRMED, KafkaTopics.BOOKING_CANCELLED},
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received booking event: eventId={}, eventType={}",
                envelope.getEventId(), envelope.getEventType());
        sendNotificationUseCase.send(
                envelope.getEventId(),
                envelope.getEventType(),
                envelope.getPayload()
        );
    }
}
