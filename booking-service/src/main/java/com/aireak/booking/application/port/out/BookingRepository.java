package com.aireak.booking.application.port.out;

import com.aireak.booking.domain.model.Booking;

import java.util.Optional;

/** Outbound port: Booking persistence. */
public interface BookingRepository {
    void save(Booking booking);
    Optional<Booking> findById(String bookingId);
}
