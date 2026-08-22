package com.aireak.payment.adapter.in.messaging;

import com.aireak.booking.domain.event.RefundRequestedEvent;
import com.aireak.common.event.EventEnvelope;
import com.aireak.common.kafka.KafkaTopics;
import com.aireak.payment.application.port.in.RefundPaymentUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound Kafka adapter: performs the refund booking-service asked for when a paid booking stopped
 * being valid — a cancelled match, or a payment that landed after the booking was already gone.
 *
 * <p>This replaced a synchronous HTTP call from booking-service whose circuit-breaker fallback
 * logged the failure and returned normally. That lost real money silently: cancelling a match with
 * hundreds of confirmed bookings could open the breaker partway through the loop and drop every
 * remaining refund, with nothing but an ERROR line to show for it. The request now rides
 * booking-service's outbox, so it is written in the same transaction as the cancellation, survives
 * this service being down, is retried by {@code KafkaConfig#kafkaErrorHandler}, and lands on a
 * dead-letter topic (never nowhere) if it still cannot be applied.
 *
 * <p>Safe to redeliver: {@link RefundPaymentUseCase#refundByBookingId} only acts on a SUCCEEDED
 * payment and moves it to REFUNDED, so a second delivery finds nothing to do and returns empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRequestedConsumer {

    private final RefundPaymentUseCase refundPaymentUseCase;

    @KafkaListener(
            topics = KafkaTopics.REFUND_REQUESTED,
            groupId = "payment-service-refund",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope<?> envelope) {
        log.debug("Received refund request: type={}, eventId={}", envelope.getEventType(), envelope.getEventId());

        if (envelope.getPayload() instanceof RefundRequestedEvent event) {
            // Anything thrown here is deliberate: the error handler retries, then dead-letters.
            // Swallowing it is what the old HTTP fallback did, and why refunds went missing.
            refundPaymentUseCase.refundByBookingId(event.bookingId(), event.reason())
                    .ifPresentOrElse(
                            paymentId -> log.info("Refund applied from event: bookingId={}, paymentId={}",
                                    event.bookingId(), paymentId),
                            () -> log.info("Refund request needs no action: bookingId={}", event.bookingId()));
            return;
        }
        log.warn("Unknown payload on {} for eventType={}", KafkaTopics.REFUND_REQUESTED, envelope.getEventType());
    }
}
