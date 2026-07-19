package com.aireak.identity.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value Object representing a validated email address.
 * Domain rule: must conform to basic email format.
 * Validation happens at construction — invalid state is impossible.
 */
public record Email(String value) {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Email {
        Objects.requireNonNull(value, "Email must not be null");
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid email format: " + value);
        }
        value = value.toLowerCase().trim();
    }

    @Override
    public String toString() {
        return value;
    }
}
