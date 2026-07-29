package com.aireak.inventory.application.port.in;

import com.aireak.inventory.application.port.in.command.HoldSeatsCommand;

import java.math.BigDecimal;

/**
 * Inbound port: place a standalone, pre-booking TTL hold on seats — called directly by the
 * frontend as soon as a customer selects a seat, well before a booking exists (see
 * {@code ReserveSeatsUseCase} for the booking-creation hold, which confirms this one over
 * instead of re-acquiring it — see {@code SeatHoldPort#confirmHold}).
 */
public interface HoldSeatsUseCase {

    /** @return the authoritative total price for the held seats, computed server-side from each seat's tier. */
    BigDecimal execute(HoldSeatsCommand command);
}
