package com.aireak.inventory.domain.event;

import com.aireak.inventory.domain.model.SeatCode;
import java.time.Instant;
import java.util.List;

/** Raised when reserved seats are released (payment failure / timeout). */
public record SeatsReleasedEvent(String showtimeId, List<SeatCode> seatCodes, Instant occurredAt) {
    public SeatsReleasedEvent(String showtimeId, List<SeatCode> seatCodes) {
        this(showtimeId, seatCodes, Instant.now());
    }
}
