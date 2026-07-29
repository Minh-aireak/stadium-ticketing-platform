package com.aireak.inventory.application.port.in;

import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;

import java.math.BigDecimal;

/** Inbound port: reserve seats for a booking (called sync by booking-service). */
public interface ReserveSeatsUseCase {

    /** @return the authoritative total price for the reserved seats, computed server-side from each seat's tier. */
    BigDecimal execute(ReserveSeatsCommand command);
}
