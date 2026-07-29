package com.aireak.catalog.domain.exception;

import com.aireak.common.exception.DomainException;

public class InvalidShowtimeException extends DomainException {
    public InvalidShowtimeException(String message) { super(message); }
}
