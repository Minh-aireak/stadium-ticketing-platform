package com.aireak.common.exception;

/**
 * The resource a request names does not exist → 404 Not Found.
 *
 * <p>Deliberately NOT a {@link DomainException}. A DomainException is a rule violation about a
 * resource that is really there, and {@code GlobalExceptionHandler} answers all of them 422 —
 * which is where "no such match" and "no such seat inventory" used to land, leaving each service
 * giving two different answers to the same question: 404 from the GET that mapped an empty
 * Optional, 422 from every write that threw.
 *
 * <p>Sits beside {@link ForbiddenException} and {@link IdentityMismatchException}, which are kept
 * out of the domain hierarchy for the same reason: their status is not 422.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
