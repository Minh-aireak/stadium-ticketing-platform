package com.aireak.common.exception;

/**
 * Thrown when a customerId/userId carried in a request body or path does not match the
 * identity {@code JwtAuthenticationFilter} authenticated for the caller. Deliberately not a
 * {@link DomainException} subtype — that maps to 422 (a correctable client input error),
 * whereas this is an authorization failure and must map to 403.
 */
public class IdentityMismatchException extends RuntimeException {

    public IdentityMismatchException(String message) {
        super(message);
    }
}
