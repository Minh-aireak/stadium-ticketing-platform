package com.aireak.identity.domain.exception;

import com.aireak.common.exception.DomainException;

/** Thrown when an email verification token is missing, unknown, or has expired. */
public class InvalidVerificationTokenException extends DomainException {
    public InvalidVerificationTokenException(String message) {
        super(message);
    }
}
