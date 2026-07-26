package com.aireak.inventory.domain.event;

import com.aireak.inventory.domain.model.SeatCode;

import java.time.Instant;
import java.util.List;

/** Raised when seats are successfully reserved for a booking. */
public record SeatsReservedEvent(
        String showtimeId,
        String bookingId,
        List<SeatCode> seatCodes,
        Instant occurredAt
) {
    public SeatsReservedEvent(String showtimeId, String bookingId, List<SeatCode> seatCodes) {
        this(showtimeId, bookingId, seatCodes, Instant.now());
    }
}
