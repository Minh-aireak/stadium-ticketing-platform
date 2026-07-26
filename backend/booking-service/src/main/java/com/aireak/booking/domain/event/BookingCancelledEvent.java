package com.aireak.booking.domain.event;

import java.time.Instant;

public record BookingCancelledEvent(
        String bookingId, String customerId, String showtimeId,
        String reason, Instant occurredAt
) {
    public BookingCancelledEvent(String bookingId, String customerId, String showtimeId, String reason) {
        this(bookingId, customerId, showtimeId, reason, Instant.now());
    }
}
