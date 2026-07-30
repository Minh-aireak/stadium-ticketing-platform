package com.aireak.inventory.application.port.in.command;

import java.util.List;

/**
 * Command to release previously reserved seats.
 *
 * @param requestingCustomerId the JWT-authenticated caller's id when this release was requested
 *                              with a real customer token (the createBooking-compensation path —
 *                              see {@code TicketInventoryRestAdapter#authorizationToken}) — the
 *                              service must verify this customer actually owns the reservation
 *                              being released before acting on it. {@code null} when the caller
 *                              used an internal-service token (every other release path), which
 *                              is trusted unconditionally and skips the ownership check.
 */
public record ReleaseSeatsCommand(String showtimeId, String bookingId, List<String> seatCodes,
                                  String requestingCustomerId) {

    public ReleaseSeatsCommand(String showtimeId, String bookingId, List<String> seatCodes) {
        this(showtimeId, bookingId, seatCodes, null);
    }
}
