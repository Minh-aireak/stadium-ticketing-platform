package com.aireak.inventory.application.port.in.command;

import java.util.List;
import java.util.Objects;

/** Seats a customer cancelled from {@code bookingId}, to be given back. */
public record ReturnSeatsCommand(String showtimeId, String bookingId, List<String> seatCodes) {

    public ReturnSeatsCommand {
        Objects.requireNonNull(showtimeId, "showtimeId must not be null");
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        seatCodes = List.copyOf(seatCodes);
    }
}
