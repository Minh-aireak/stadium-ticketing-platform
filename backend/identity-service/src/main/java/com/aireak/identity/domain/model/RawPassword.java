package com.aireak.identity.domain.model;

import com.aireak.identity.domain.exception.InvalidPasswordException;

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
        // length() counts UTF-16 code units, which is deliberate: the frontend gates on
        // String.prototype.length and that counts the same units, so the two agree by
        // construction. The character classes below must not use the same unit.
        if (password.length() < 8) {
            throw new InvalidPasswordException("Password must be at least 8 characters");
        }
        // codePoints(), not chars(). Character.isUpperCase/isLowerCase/isDigit take a code
        // point; chars() hands them UTF-16 code units, so every character above U+FFFF arrived
        // as two lone surrogates and a lone surrogate is neither a letter nor a digit. A
        // password whose only uppercase letter is, say, ADLAM CAPITAL ALIF (U+1E900) was
        // rejected here for having no uppercase letter — while the frontend's /\p{Uppercase}/u
        // waved it through, which is the direction that costs the customer a broken form
        // rather than the server a bad row.
        if (password.codePoints().noneMatch(Character::isUpperCase)) {
            throw new InvalidPasswordException("Password must contain at least one uppercase letter");
        }
        if (password.codePoints().noneMatch(Character::isLowerCase)) {
            throw new InvalidPasswordException("Password must contain at least one lowercase letter");
        }
        if (password.codePoints().noneMatch(Character::isDigit)) {
            throw new InvalidPasswordException("Password must contain at least one digit");
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
