package com.aireak.identity.domain.model;

import java.util.Objects;

/**
 * Value Object representing a raw (unhashed) password.
 * Enforces password policy at construction time — domain rule lives here.
 *
 * <p>Policy: minimum 8 characters, at least one uppercase, one lowercase, one digit.
 * For more complex history-check policies (e.g. "can't reuse last 5"), extract to
 * a PasswordPolicyService domain service.
 *
 * <p>IMPORTANT: never log, serialize, or expose the raw value externally.
 */
public final class RawPassword {

    private final String value;

    public RawPassword(String value) {
        Objects.requireNonNull(value, "Password must not be null");
        validate(value);
        this.value = value;
    }

    private RawPassword(String value, boolean skipPolicyCheck) {
        this.value = value;
    }

    /**
     * Wraps a password supplied for an authentication attempt, without enforcing the
     * current registration policy. Login must reject on a wrong password, not on a
     * policy mismatch — a password valid under an older policy must still be checkable,
     * and the caller should see a uniform "invalid credentials" error either way.
     */
    public static RawPassword forAuthentication(String value) {
        Objects.requireNonNull(value, "Password must not be null");
        return new RawPassword(value, true);
    }

    private void validate(String password) {
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (!password.chars().anyMatch(Character::isUpperCase)) {
            throw new IllegalArgumentException("Password must contain at least one uppercase letter");
        }
        if (!password.chars().anyMatch(Character::isLowerCase)) {
            throw new IllegalArgumentException("Password must contain at least one lowercase letter");
        }
        if (!password.chars().anyMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Password must contain at least one digit");
        }
    }

    /**
     * Returns the raw value for hashing purposes ONLY.
     * Must not be stored, logged, or returned to clients.
     */
    public String exposeForHashing() {
        return value;
    }

    @Override
    public String toString() {
        return "***"; // prevent accidental logging
    }
}
