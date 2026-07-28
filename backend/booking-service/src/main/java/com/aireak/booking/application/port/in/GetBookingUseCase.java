package com.aireak.booking.application.port.in;

import com.aireak.booking.domain.model.Booking;

import java.util.Optional;

/** Inbound port: look up a booking by id. */
public interface GetBookingUseCase {
    Optional<Booking> getBooking(String bookingId);
}
