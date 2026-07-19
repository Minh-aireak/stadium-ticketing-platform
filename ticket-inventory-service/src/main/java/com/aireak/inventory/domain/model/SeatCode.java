package com.aireak.inventory.domain.model;

import java.util.Objects;

/**
 * Value Object: identifies a specific seat (e.g. "A12", "B3").
 * Format: row-letter + seat-number. Validated at construction.
 */
public record SeatCode(String value) {

    public SeatCode {
        Objects.requireNonNull(value, "SeatCode must not be null");
        if (!value.matches("^[A-Z]\\d{1,3}$")) {
            throw new IllegalArgumentException("Invalid seat code format: " + value +
                    " (expected e.g. A12, B3)");
        }
    }

    @Override
    public String toString() { return value; }
}
