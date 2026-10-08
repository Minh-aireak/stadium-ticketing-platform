package com.aireak.inventory.application.port.in;

import com.aireak.inventory.application.port.in.command.ReturnSeatsCommand;

/**
 * Inbound port: give back seats a customer cancelled — drop the booking's holds on them, and put
 * the ones it had bought back on sale. Idempotent, because it is driven by an at-least-once event.
 */
public interface ReturnSeatsUseCase {
    void execute(ReturnSeatsCommand command);
}
