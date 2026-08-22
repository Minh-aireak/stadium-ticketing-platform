package com.aireak.booking.domain.event;

import java.time.Instant;

/**
 * Consumer-side copy of booking-service's event of the same name — same package and shape, since
 * {@code EventEnvelope} resolves the payload by class name (see the polymorphic type validator in
 * {@code KafkaConfig}). Same convention booking-service uses for payment-service's events.
 */
public record RefundRequestedEvent(String bookingId, String reason, Instant occurredAt) {
}
