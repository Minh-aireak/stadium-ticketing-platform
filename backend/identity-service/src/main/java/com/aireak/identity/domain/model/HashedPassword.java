package com.aireak.identity.domain.model;

import java.util.Objects;

/**
 * Value Object representing a BCrypt-hashed password.
 * Pure data — no validation logic (hash is already a valid hash string).
 * Wraps the hash to prevent treating raw and hashed passwords interchangeably.
 */
public record HashedPassword(String value) {

    public HashedPassword {
        Objects.requireNonNull(value, "HashedPassword value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("HashedPassword must not be blank");
        }
    }

    @Override
    public String toString() {
        return "***"; // prevent accidental hash logging
    }
}
