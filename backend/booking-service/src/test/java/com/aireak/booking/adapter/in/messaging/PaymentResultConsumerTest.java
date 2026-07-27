package com.aireak.booking.adapter.in.messaging;

import com.aireak.booking.application.service.BookingOrchestrationService;
import com.aireak.common.event.EventEnvelope;
import com.aireak.payment.domain.event.PaymentFailedEvent;
import com.aireak.payment.domain.event.PaymentSucceededEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static com.aireak.common.kafka.KafkaTopics.PAYMENT_FAILED;
import static com.aireak.common.kafka.KafkaTopics.PAYMENT_SUCCEEDED;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Exercises the saga-driving Kafka consumer with the same {@link EventEnvelope} shape
 * {@code PaymentEventPublisher} (payment-service) actually produces: {@code eventType} is the
 * {@code KafkaTopics} routing string (not a Java class name — see {@code EventEnvelope.of}), and
 * the payload arrives as the concrete event record.
 *
 * <p>{@link PaymentSucceededEvent}/{@link PaymentFailedEvent} here are booking-service's own
 * local copies of payment-service's domain events (booking-service has no module dependency on
 * payment-service), kept field-for-field identical so {@code @JsonTypeInfo(use = Id.CLASS)} on
 * {@code EventEnvelope.payload} resolves them by fully-qualified class name — the same pattern
 * notification-service already uses for booking/identity events.
 */
@ExtendWith(MockitoExtension.class)
class PaymentResultConsumerTest {

    @Mock
    private BookingOrchestrationService bookingOrchestrationService;

    private PaymentResultConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentResultConsumer(bookingOrchestrationService);
    }

    @Test
    void consume_paymentSucceededAsProducedInProduction_confirmsBooking() {
        PaymentSucceededEvent succeeded = new PaymentSucceededEvent(
                "payment-1", "booking-1", new BigDecimal("150.00"), "USD", "gw-txn-1", Instant.now());
        EventEnvelope<PaymentSucceededEvent> envelope = EventEnvelope.of(PAYMENT_SUCCEEDED, succeeded, null);

        consumer.consume(envelope);

        verify(bookingOrchestrationService).confirmBooking("booking-1");
    }

    @Test
    void consume_paymentFailedAsProducedInProduction_cancelsBookingWithReasonFromTheEvent() {
        PaymentFailedEvent failed = new PaymentFailedEvent("payment-1", "booking-1", "card declined", Instant.now());
        EventEnvelope<PaymentFailedEvent> envelope = EventEnvelope.of(PAYMENT_FAILED, failed, null);

        consumer.consume(envelope);

        verify(bookingOrchestrationService).cancelBookingOnPaymentFailure("booking-1", "card declined");
    }

    @Test
    void consume_unrecognizedPayload_doesNothing() {
        EventEnvelope<String> envelope = EventEnvelope.of(PAYMENT_SUCCEEDED, "not-a-payment-event", null);

        consumer.consume(envelope);

        verifyNoInteractions(bookingOrchestrationService);
    }
}
