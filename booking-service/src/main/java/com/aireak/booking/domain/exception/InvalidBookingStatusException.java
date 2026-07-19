package com.aireak.booking.domain.exception;

import com.aireak.common.exception.DomainException;

public class InvalidBookingStatusException extends DomainException {
    public InvalidBookingStatusException(String message) { super(message); }
}
