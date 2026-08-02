package com.aireak.catalog.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mirror of match-catalog-service's {@code ShowtimeAddedEvent} — same package/class name so
 * Jackson's {@code @JsonTypeInfo(use = Id.CLASS)} polymorphic resolution on {@code
 * EventEnvelope.payload} can deserialize it here (see {@code
 * com.aireak.booking.domain.event.BookingConfirmedEvent} in notification-service for the same
 * established pattern). Keep field-for-field in sync with the producer.
 */
public record ShowtimeAddedEvent(
        String matchId,
        String showtimeId,
        String stadiumId,
        int totalSeats,
        BigDecimal basePrice,
        String currency,
        Instant occurredAt
) {
}
