package com.aireak.inventory.domain.exception;

import com.aireak.common.exception.DomainException;

public class ShowtimeBookingClosedException extends DomainException {
    public ShowtimeBookingClosedException(String showtimeId) {
        super("Ticket booking is closed for showtime: " + showtimeId);
    }

    /**
     * For a definite "no" that arrived as an exception — match-catalog-service answering 4xx,
     * typically 404 for a showtime it does not know. Same sentence as above on purpose: the
     * customer's situation is identical either way, and only the cause, kept here for the log,
     * differs. An outage is NOT this: see {@link ShowtimeCatalogUnavailableException}.
     */
    public ShowtimeBookingClosedException(String showtimeId, Throwable cause) {
        super("Ticket booking is closed for showtime: " + showtimeId, cause);
    }
}
