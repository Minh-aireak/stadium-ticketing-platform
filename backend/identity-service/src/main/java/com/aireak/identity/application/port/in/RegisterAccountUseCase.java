package com.aireak.identity.application.port.in;

import com.aireak.identity.application.port.in.command.RegisterAccountCommand;

/**
 * Inbound port: register a new account.
 * Driving adapter (AuthController) calls this.
 *
 * @return the generated accountId as String
 */
public interface RegisterAccountUseCase {
    String execute(RegisterAccountCommand command);
}
