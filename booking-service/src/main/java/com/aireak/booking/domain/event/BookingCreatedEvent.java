package com.aireak.booking.domain.event;

import java.time.Instant;

public record BookingCreatedEvent(String bookingId, String customerId, String showtimeId, Instant occurredAt) {
    public BookingCreatedEvent(String bookingId, String customerId, String showtimeId) {
        this(bookingId, customerId, showtimeId, Instant.now());
    }
}
