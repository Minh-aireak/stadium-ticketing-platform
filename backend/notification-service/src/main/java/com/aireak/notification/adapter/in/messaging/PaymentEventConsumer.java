package com.aireak.notification.adapter.in.messaging;

import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.notification.application.port.in.SendNotificationUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer adapter for payment-related domain events — drives the payment-success receipt
 * email and the refund-issued email. Idempotency is handled by the application service, not here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    /**
     * Named rather than inlined because {@link NotificationDeadLetterConsumer} has to recognise
     * this group on a dead-lettered record: {@code payment.payment.succeeded} is consumed by
     * booking-service too and both services share the one {@code -dlt} topic.
     */
    public static final String GROUP_ID = "notification-service-payment";

    private final SendNotificationUseCase sendNotificationUseCase;

    @KafkaListener(
            topics = {KafkaTopics.PAYMENT_SUCCEEDED, KafkaTopics.PAYMENT_REFUNDED},
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received payment event: eventId={}, eventType={}",
                envelope.getEventId(), envelope.getEventType());
        sendNotificationUseCase.send(
                envelope.getEventId(),
                envelope.getEventType(),
                envelope.getPayload()
        );
    }
}
