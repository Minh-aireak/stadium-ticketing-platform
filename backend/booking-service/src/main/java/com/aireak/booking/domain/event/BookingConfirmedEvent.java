package com.aireak.booking.domain.event;

import com.aireak.booking.domain.model.BookingAmount;
import java.time.Instant;
import java.util.List;

public record BookingConfirmedEvent(
        String bookingId, String customerId, String customerEmail, String showtimeId,
        List<String> seatCodes, BookingAmount amount, Instant occurredAt
) {
    public BookingConfirmedEvent(String bookingId, String customerId, String customerEmail, String showtimeId,
                                  List<String> seatCodes, BookingAmount amount) {
        this(bookingId, customerId, customerEmail, showtimeId, seatCodes, amount, Instant.now());
    }
}
