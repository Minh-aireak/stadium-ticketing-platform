package com.aireak.booking.adapter.in.messaging;

import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Inbound Kafka adapter: listens for payment result events to drive saga.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResultConsumer {

    /**
     * Named rather than inlined because {@link PaymentResultDeadLetterConsumer} has to recognise
     * this group on a dead-lettered record: {@code payment.payment.succeeded} is consumed by
     * notification-service too and both services share the one {@code -dlt} topic.
     */
    public static final String GROUP_ID = "booking-service-payment";

    private final BookingOrchestrationService bookingOrchestrationService;

    @KafkaListener(
            topics = {KafkaTopics.PAYMENT_SUCCEEDED, KafkaTopics.PAYMENT_FAILED},
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received payment event: type={}, eventId={}", envelope.getEventType(), envelope.getEventId());

        switch (envelope.getPayload()) {
            case PaymentSucceededEvent e -> bookingOrchestrationService.confirmBooking(e.bookingId());
            case PaymentFailedEvent e -> bookingOrchestrationService.cancelBookingOnPaymentFailure(
                    e.bookingId(), e.reason());
            default -> log.warn("Unknown payment event payload for eventType={}", envelope.getEventType());
        }
    }
}
