package com.aireak.identity.application.port.in;

import com.aireak.identity.application.port.in.command.LoginCommand;

/**
 * Inbound port: authenticate an account and return a JWT token.
 *
 * @return signed JWT token string
 */
public interface LoginUseCase {
    String execute(LoginCommand command);
}
