package com.aireak.booking.application.port.in;

import com.aireak.booking.application.port.in.dto.BookingCreationResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inbound port: create a booking (saga: draft booking -> reserve seats -> pending payment ->
 * initiate payment). Driving adapter (BookingController) calls this.
 */
public interface CreateBookingUseCase {

    // `amount` is client-supplied and used only as a display/log placeholder for the draft
    // row — the saga overwrites it with the server-computed price from ticket-inventory-service
    // before any charge-relevant step. Never trust it for the actual charge.
    BookingCreationResult createBooking(String idempotencyKey, String customerId, String showtimeId,
            List<String> seatCodes, BigDecimal amount, String currency);
}
