package com.aireak.booking.domain.exception;

/**
 * The booking changed between the moment a cancel request was checked and the moment it was
 * applied — a payment landed and made an unpaid booking paid, or another writer got there first.
 * A 409: the request was not wrong, it was answered from a state that no longer holds, and asking
 * again is answered from the new one.
 */
public class CancellationConflictException extends RuntimeException {

    public CancellationConflictException(String bookingId) {
        super("Booking " + bookingId + " changed while it was being cancelled; try again");
    }

    public CancellationConflictException(String bookingId, Throwable cause) {
        super("Booking " + bookingId + " changed while it was being cancelled; try again", cause);
    }
}
