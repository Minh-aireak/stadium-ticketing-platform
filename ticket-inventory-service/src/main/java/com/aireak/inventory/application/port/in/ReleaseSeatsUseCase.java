package com.aireak.inventory.application.port.in;

import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;

/** Inbound port: release previously reserved seats (compensating action in saga). */
public interface ReleaseSeatsUseCase {
    void execute(ReleaseSeatsCommand command);
}
