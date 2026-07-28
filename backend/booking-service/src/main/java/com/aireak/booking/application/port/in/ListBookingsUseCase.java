package com.aireak.booking.application.port.in;

import com.aireak.booking.domain.model.Booking;

import java.util.List;

/** Inbound port: "my tickets" — bookings belonging to one customer, newest first. */
public interface ListBookingsUseCase {

    BookingPage listByCustomer(String customerId, int page, int size);

    record BookingPage(List<Booking> items, long totalElements, int page, int size) {}
}
