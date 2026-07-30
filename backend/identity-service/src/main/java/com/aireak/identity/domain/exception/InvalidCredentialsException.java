package com.aireak.identity.domain.exception;

import com.aireak.common.exception.DomainException;

/**
 * Thrown when a login attempt's email/password does not resolve to an active account — covers
 * unknown email, wrong password, and a non-ACTIVE account uniformly (see LoginService) so the
 * response never differs by cause. Mapped to 401 Unauthorized in AuthController (not the generic
 * 422 {@link DomainException} mapping) since this is an authentication failure, not a correctable
 * domain rule violation.
 */
public class InvalidCredentialsException extends DomainException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
