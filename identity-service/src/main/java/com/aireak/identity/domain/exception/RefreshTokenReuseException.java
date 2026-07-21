package com.aireak.identity.domain.exception;

import com.aireak.common.exception.DomainException;

/**
 * Thrown when a refresh token that was already rotated (revoked) is presented again.
 * Signals that the token family has been compromised — the caller must have already
 * revoked the whole family before this is thrown, and must force the user to re-login.
 */
public class RefreshTokenReuseException extends DomainException {
    public RefreshTokenReuseException(String message) {
        super(message);
    }
}
