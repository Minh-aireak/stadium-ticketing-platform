package com.aireak.notification.adapter.in.messaging;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.notification.application.port.in.SendNotificationUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer adapter for identity-related domain events.
 * Handles AccountRegisteredEvent (welcome/verification email), AccountActivatedEvent
 * (activation confirmation email) and PasswordResetRequestedEvent (reset-link email).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventConsumer {

    /** Named rather than inlined so {@link NotificationDeadLetterConsumer} can recognise it. */
    public static final String GROUP_ID = "notification-service-identity";

    private final SendNotificationUseCase sendNotificationUseCase;

    @KafkaListener(
            topics = {KafkaTopics.ACCOUNT_REGISTERED, KafkaTopics.ACCOUNT_ACTIVATED,
                    KafkaTopics.PASSWORD_RESET_REQUESTED},
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received account event: eventId={}, eventType={}",
                envelope.getEventId(), envelope.getEventType());
        sendNotificationUseCase.send(
                envelope.getEventId(),
                envelope.getEventType(),
                envelope.getPayload()
        );
    }
}
