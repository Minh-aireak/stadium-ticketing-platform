package com.aireak.booking.adapter.in.messaging;

import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

// Inbound Kafka adapter: listens for payment result events to drive saga.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResultConsumer {

    private final BookingOrchestrationService bookingOrchestrationService;

    @SuppressWarnings("unchecked")
    @KafkaListener(
            topics = {KafkaTopics.PAYMENT_SUCCEEDED, KafkaTopics.PAYMENT_FAILED},
            groupId = "booking-service-payment",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received payment event: type={}, eventId={}", envelope.getEventType(), envelope.getEventId());

        Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
        String bookingId = (String) payload.get("bookingId");

        switch (envelope.getEventType()) {
            case KafkaTopics.PAYMENT_SUCCEEDED -> bookingOrchestrationService.confirmBooking(bookingId);
            case KafkaTopics.PAYMENT_FAILED    -> {
                String showtimeId = (String) payload.get("showtimeId");
                @SuppressWarnings("unchecked")
                var seatCodes = (java.util.List<String>) payload.get("seatCodes");
                String reason = (String) payload.getOrDefault("reason", "Payment failed");
                bookingOrchestrationService.cancelBookingOnPaymentFailure(bookingId, showtimeId, seatCodes, reason);
            }
            default -> log.warn("Unknown payment event type: {}", envelope.getEventType());
        }
    }
}
