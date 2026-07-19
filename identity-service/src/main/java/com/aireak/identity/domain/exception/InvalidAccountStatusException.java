package com.aireak.identity.domain.exception;

import com.aireak.common.exception.DomainException;

/** Thrown when an invalid state transition is attempted on an Account. */
public class InvalidAccountStatusException extends DomainException {
    public InvalidAccountStatusException(String message) {
        super(message);
    }
}
