package com.aireak.booking.application.service;

/**
 * Another cancel request for the same booking holds the cancellation lock right now. Answered 409
 * at once, without waiting: the request that holds it finishes within milliseconds, and asking again
 * then is answered from what it did.
 */
public class CancellationInProgressException extends RuntimeException {

    public CancellationInProgressException(String bookingId) {
        super("Another cancellation of booking " + bookingId + " is in progress; try again in a moment");
    }
}
