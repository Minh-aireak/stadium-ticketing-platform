package com.aireak.identity.domain.model;

import com.aireak.identity.domain.exception.InvalidEmailException;

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
        value = value.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new InvalidEmailException("Invalid email format: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
