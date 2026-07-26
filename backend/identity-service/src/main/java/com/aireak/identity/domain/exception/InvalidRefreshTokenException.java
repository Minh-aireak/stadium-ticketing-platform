package com.aireak.identity.domain.exception;

import com.aireak.common.exception.DomainException;

/** Thrown when a refresh token is malformed, unknown, expired, or its family was revoked. */
public class InvalidRefreshTokenException extends DomainException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
