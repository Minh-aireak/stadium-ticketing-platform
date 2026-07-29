package com.aireak.booking.domain.event;

import java.time.Instant;

public record BookingCancelledEvent(
        String bookingId, String customerId, String customerEmail, String showtimeId,
        String reason, Instant occurredAt
) {}
