package com.aireak.inventory.application.port.in;

import com.aireak.inventory.application.port.in.command.UnholdSeatsCommand;

/**
 * Inbound port: release a standalone pre-booking hold — called by the frontend when a
 * customer deselects a seat, or leaves seat selection without checking out.
 */
public interface UnholdSeatsUseCase {
    void execute(UnholdSeatsCommand command);
}
