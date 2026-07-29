package com.aireak.identity.domain.exception;

import com.aireak.common.exception.DomainException;

/** Thrown when a raw password fails the registration password policy. */
public class InvalidPasswordException extends DomainException {
    public InvalidPasswordException(String message) {
        super(message);
    }
}
