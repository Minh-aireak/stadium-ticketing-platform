package com.aireak.inventory.application.port.in;

import com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand;

/** Inbound port: finalize a sale — marks held seats SOLD (called on payment success). */
public interface ConfirmSeatsUseCase {
    void execute(ConfirmSeatsCommand command);
}
