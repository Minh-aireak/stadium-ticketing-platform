package com.aireak.booking.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BookingConfirmedEvent(
        String bookingId, String customerId, String showtimeId,
        List<String> seatCodes, BookingAmount amount, Instant occurredAt
) {
    public record BookingAmount(BigDecimal amount, String currency) {}
}
