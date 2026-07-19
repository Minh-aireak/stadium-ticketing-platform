package com.aireak.booking.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Value Object: the seat selection made by a customer.
 * Encapsulates seat codes + total count for validation purposes.
 *
 * <p>Domain rule enforced here: seatCodes must not be empty.
 */
public record SeatSelection(List<String> seatCodes) {

    public SeatSelection {
        Objects.requireNonNull(seatCodes, "seatCodes must not be null");
        if (seatCodes.isEmpty()) {
            throw new IllegalArgumentException("SeatSelection must contain at least one seat");
        }
        seatCodes = List.copyOf(seatCodes); // immutable copy
    }

    public int count() { return seatCodes.size(); }
}
