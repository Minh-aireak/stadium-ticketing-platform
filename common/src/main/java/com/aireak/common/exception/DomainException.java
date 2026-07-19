package com.aireak.common.exception;

/**
 * Base unchecked exception for all domain-level errors.
 * Services extend this with their own specific domain exceptions.
 * Never catches or wraps infrastructure exceptions directly.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
