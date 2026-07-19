package com.aireak.booking.domain.exception;

import com.aireak.common.exception.DomainException;

public class MaxTicketsExceededException extends DomainException {
    public MaxTicketsExceededException(int requested, int max) {
        super("Max " + max + " tickets per booking, requested: " + requested);
    }
}
