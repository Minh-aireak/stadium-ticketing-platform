package com.aireak.booking.domain.event;

import com.aireak.booking.domain.model.BookingAmount;
import java.time.Instant;
import java.util.List;

public record BookingCreatedEvent(
        String bookingId, String customerId, String showtimeId,
        List<String> seatCodes, BookingAmount amount, Instant occurredAt
) {
    public BookingCreatedEvent(String bookingId, String customerId, String showtimeId,
                                List<String> seatCodes, BookingAmount amount) {
        this(bookingId, customerId, showtimeId, seatCodes, amount, Instant.now());
    }
}
