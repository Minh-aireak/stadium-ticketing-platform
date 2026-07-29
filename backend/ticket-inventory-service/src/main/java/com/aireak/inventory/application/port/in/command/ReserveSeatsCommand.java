package com.aireak.inventory.application.port.in.command;

import java.util.List;

/**
 * Command to reserve seats for a booking.
 *
 * @param customerId the JWT-authenticated caller who owns any pre-existing standalone hold on
 *                    these seats (see {@code HoldSeatsUseCase}) — used to confirm that hold over
 *                    into this booking instead of re-acquiring it from scratch (see
 *                    {@code SeatHoldPort#confirmHold}).
 */
public record ReserveSeatsCommand(String showtimeId, String bookingId, String customerId, List<String> seatCodes) {}
