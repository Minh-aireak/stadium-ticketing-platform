package com.aireak.booking.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Value Object: monetary amount with currency.
 * Domain rule: amount must be positive.
 */
public record BookingAmount(BigDecimal amount, String currency) {

    public BookingAmount {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("BookingAmount must be positive, got: " + amount);
        }
        if (currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be ISO 4217 3-letter code, got: " + currency);
        }
    }

    public static BookingAmount of(BigDecimal amount, String currency) {
        return new BookingAmount(amount, currency);
    }

    public static BookingAmount vnd(BigDecimal amount) {
        return new BookingAmount(amount, "VND");
    }
}
