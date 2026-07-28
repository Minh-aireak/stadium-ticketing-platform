package com.aireak.booking.application.port.in.dto;

import com.aireak.booking.application.port.in.CreateBookingUseCase;
import com.aireak.booking.domain.model.BookingStatus;

/**
 * Result of {@link CreateBookingUseCase#createBooking}: callers must read {@code status}, not
 * just presence of a bookingId, to know whether the booking is finalized.
 */
public record BookingCreationResult(String bookingId, BookingStatus status) {}
