package com.aireak.identity.domain.model;

/**
 * Lifecycle states of an Account aggregate.
 *
 * <p>Valid transitions:
 * <pre>
 *   PENDING_VERIFICATION → ACTIVE       (email verified)
 *   ACTIVE              → SUSPENDED     (admin action / policy violation)
 *   SUSPENDED           → ACTIVE        (admin re-activation)
 *   PENDING_VERIFICATION → (deleted)    (if unverified after TTL)
 * </pre>
 */
public enum AccountStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED
}
