package com.aireak.identity.domain.model;

import com.aireak.identity.domain.exception.InvalidEmailException;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value Object representing a validated email address.
 * Domain rule: must conform to basic email format and fit the column that stores it.
 * Validation happens at construction — invalid state is impossible.
 */
public record Email(String value) {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * 254 is the longest address RFC 5321 permits — a 256-octet path minus the two angle brackets
     * — and it is also what {@code accounts.email} can hold, VARCHAR(255) since V1__init_schema.
     * The same address is later denormalized onto booking-service's and payment-service's own
     * VARCHAR(255) {@code customer_email} columns, so bounding it here bounds all three.
     *
     * <p>Nothing bounded it before. {@code @Email} on RegisterRequest checks shape, not length,
     * and the pattern above accepts a local part of any size, so an over-long address reached the
     * INSERT and Postgres answered "value too long for type character varying(255)". Because
     * {@code save()} is a persist that does not flush (see AccountPersistenceAdapter), that landed
     * at commit as a DataIntegrityViolationException, which GlobalExceptionHandler can only report
     * as 409 "The request conflicts with existing data" — on the public, unauthenticated
     * registration endpoint.
     */
    private static final int MAX_LENGTH = 254;

    public Email {
        Objects.requireNonNull(value, "Email must not be null");
        value = value.trim().toLowerCase();
        // Length before format, so an over-long value is reported by its length rather than
        // echoed back in full by the format message below.
        if (value.length() > MAX_LENGTH) {
            throw new InvalidEmailException("Email must be at most " + MAX_LENGTH + " characters");
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new InvalidEmailException("Invalid email format: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
