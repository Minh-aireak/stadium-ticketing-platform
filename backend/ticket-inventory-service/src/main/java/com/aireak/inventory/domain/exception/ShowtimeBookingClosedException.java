package com.aireak.inventory.domain.exception;

import com.aireak.common.exception.DomainException;

public class ShowtimeBookingClosedException extends DomainException {
    public ShowtimeBookingClosedException(String showtimeId) {
        super("Ticket booking is closed for showtime: " + showtimeId);
    }

    public ShowtimeBookingClosedException(String showtimeId, Throwable cause) {
        super("Unable to verify booking window for showtime: " + showtimeId, cause);
    }
}
