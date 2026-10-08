package com.aireak.booking.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Consumer-side copy of booking-service's event of the same name — same package and shape, since
 * {@code EventEnvelope} resolves the payload by class name (see the polymorphic type validator in
 * {@code KafkaConfig}). Same convention booking-service uses for payment-service's events.
 *
 * <p>{@code amount} null means "everything still refundable"; {@code refundRequestId} is what makes a
 * redelivery a no-op now that a payment can be refunded in parts. See booking-service's copy.
 */
public record RefundRequestedEvent(String bookingId, String refundRequestId, BigDecimal amount, String currency,
                                   List<String> seatCodes, String reason, Instant occurredAt) {
}
