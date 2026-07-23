package com.aireak.booking.domain.model;

import java.util.List;
import java.util.Objects;

// Shape-only invariants (non-empty, immutable) live here because this constructor also runs
// when reconstituting a SeatSelection from an already-persisted booking row (see
// BookingPersistenceAdapter#toDomain) — business rules that should only gate NEW input (max
// tickets, no duplicate seats) belong in Booking.create() instead, same as MAX_TICKETS, so a
// row written before such a rule existed can still be read back.
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
