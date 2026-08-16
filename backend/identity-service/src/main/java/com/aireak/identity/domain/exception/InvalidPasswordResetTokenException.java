package com.aireak.identity.domain.exception;

import com.aireak.common.exception.DomainException;

/** Thrown when a password reset token is missing, unknown, or has expired. */
public class InvalidPasswordResetTokenException extends DomainException {
    public InvalidPasswordResetTokenException(String message) {
        super(message);
    }
}
