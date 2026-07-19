package com.aireak.inventory.domain.event;

import com.aireak.inventory.domain.model.SeatCode;
import java.time.Instant;
import java.util.List;

/** Raised when seats are sold (payment confirmed). */
public record SeatsSoldEvent(String showtimeId, List<SeatCode> seatCodes, Instant occurredAt) {
    public SeatsSoldEvent(String showtimeId, List<SeatCode> seatCodes) {
        this(showtimeId, seatCodes, Instant.now());
    }
}
