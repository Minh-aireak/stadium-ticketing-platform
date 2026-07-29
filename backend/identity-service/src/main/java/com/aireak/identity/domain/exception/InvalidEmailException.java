package com.aireak.identity.domain.exception;

import com.aireak.common.exception.DomainException;

/** Thrown when an email address fails format validation. */
public class InvalidEmailException extends DomainException {
    public InvalidEmailException(String message) {
        super(message);
    }
}
