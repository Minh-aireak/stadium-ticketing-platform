package com.aireak.identity.domain.exception;

import com.aireak.common.exception.DomainException;

/** Thrown when trying to register an email that already exists. */
public class EmailAlreadyRegisteredException extends DomainException {
    public EmailAlreadyRegisteredException(String email) {
        super("Email already registered: " + email);
    }
}
