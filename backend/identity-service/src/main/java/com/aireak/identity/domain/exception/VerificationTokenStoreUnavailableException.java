package com.aireak.identity.domain.exception;

/**
 * Thrown when Redis is unreachable/times out while storing or reading an email verification
 * token. Infra fault, not a client-correctable domain violation — deliberately does NOT extend
 * {@link com.aireak.common.exception.DomainException} so it isn't mapped to 422.
 */
public class VerificationTokenStoreUnavailableException extends RuntimeException {
    public VerificationTokenStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
