package com.aireak.booking.application.port.in;

import com.aireak.booking.application.port.in.dto.BookingCreationResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inbound port: create a booking (saga: draft booking -> reserve seats -> pending payment ->
 * initiate payment). Driving adapter (BookingController) calls this.
 */
public interface CreateBookingUseCase {
    BookingCreationResult createBooking(String idempotencyKey, String customerId, String showtimeId,
            List<String> seatCodes, BigDecimal amount, String currency);
}
