package com.aireak.catalog.domain.exception;

import com.aireak.common.exception.DomainException;

public class InvalidMatchStatusException extends DomainException {
    public InvalidMatchStatusException(String message) { super(message); }
}
