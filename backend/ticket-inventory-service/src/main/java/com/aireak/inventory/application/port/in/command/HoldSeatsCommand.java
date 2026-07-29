package com.aireak.inventory.application.port.in.command;

import java.util.List;

/** Command to place a standalone pre-booking hold, owned by the authenticated customer. */
public record HoldSeatsCommand(String showtimeId, String customerId, List<String> seatCodes) {}
