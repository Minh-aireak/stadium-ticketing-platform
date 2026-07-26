package com.aireak.identity.domain.exception;

/**
 * Thrown when Redis is unreachable/times out while reading or writing refresh session state.
 * Infra fault, not a client-correctable domain violation — deliberately does NOT extend
 * {@link com.aireak.common.exception.DomainException} so it isn't mapped to 422.
 */
public class RefreshSessionStoreUnavailableException extends RuntimeException {
    public RefreshSessionStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
