package com.aireak.booking.domain.exception;

import com.aireak.common.exception.DomainException;

/**
 * The seats named in a cancel request cannot be cancelled as asked: they are not this booking's,
 * or the booking is unpaid and can only be cancelled as a whole. A 422 — the request was understood
 * and refused on the booking's merits.
 */
public class SeatCancellationException extends DomainException {

    public SeatCancellationException(String message) {
        super(message);
    }
}
