package com.aireak.inventory.application.port.in;

import com.aireak.inventory.application.port.in.command.ReserveSeatsCommand;

/** Inbound port: reserve seats for a booking (called sync by booking-service). */
public interface ReserveSeatsUseCase {
    void execute(ReserveSeatsCommand command);
}
