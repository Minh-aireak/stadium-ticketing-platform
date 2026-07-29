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
    //
    // `customerEmail` comes from the authenticated caller's own JWT (see BookingController), not
    // client input — it is denormalized onto Booking so BookingConfirmedEvent/BookingCancelledEvent
    // can carry a real address without notification-service calling back into identity-service.
    BookingCreationResult createBooking(String idempotencyKey, String customerId, String customerEmail,
            String showtimeId, List<String> seatCodes, BigDecimal amount, String currency);
}
