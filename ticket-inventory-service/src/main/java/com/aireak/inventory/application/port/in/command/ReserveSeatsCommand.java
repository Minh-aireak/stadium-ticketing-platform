package com.aireak.inventory.application.port.in.command;

import java.util.List;

/** Command to reserve seats for a booking. */
public record ReserveSeatsCommand(String showtimeId, String bookingId, List<String> seatCodes) {}
