package com.aireak.inventory.application.port.in.command;

import java.util.List;

/** Command to release a standalone pre-booking hold owned by the authenticated customer. */
public record UnholdSeatsCommand(String showtimeId, String customerId, List<String> seatCodes) {}
