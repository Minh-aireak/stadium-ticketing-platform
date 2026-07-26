package com.aireak.inventory.application.port.in.command;

import java.util.List;

/** Command to finalize a sale: marks held seats SOLD (payment confirmed). */
public record ConfirmSeatsCommand(String showtimeId, String bookingId, List<String> seatCodes) {}
