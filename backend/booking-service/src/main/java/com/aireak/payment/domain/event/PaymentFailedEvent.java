package com.aireak.payment.domain.event;

import java.time.Instant;

/**
 * Local copy of payment-service's domain event, kept in the same package and shape so
 * {@code EventEnvelope}'s {@code @JsonTypeInfo(use = Id.CLASS)} payload deserialization
 * resolves it by fully-qualified class name — see {@code PaymentResultConsumer}. booking-service
 * does not depend on payment-service's module, so this cannot be a shared class; it must stay
 * field-for-field identical to {@code payment-service}'s {@code PaymentFailedEvent}.
 */
public record PaymentFailedEvent(
        String paymentId, String bookingId, String reason, Instant occurredAt
) {}
