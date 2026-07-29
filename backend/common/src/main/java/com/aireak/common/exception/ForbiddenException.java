package com.aireak.common.exception;

/**
 * Thrown when an authenticated caller's role does not satisfy an endpoint's authorization
 * requirement (e.g. a USER calling an ADMIN-only match-management endpoint). Distinct from
 * {@link IdentityMismatchException} (identity mismatch) and {@link DomainException} (422,
 * correctable input) — this is a role/permission failure and must map to 403.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
