package com.aireak.inventory.application.port.in.command;

import java.util.List;

/** Command to release previously reserved seats. */
public record ReleaseSeatsCommand(String showtimeId, String bookingId, List<String> seatCodes) {}
