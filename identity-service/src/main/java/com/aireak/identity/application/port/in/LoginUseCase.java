package com.aireak.identity.application.port.in;

import com.aireak.identity.application.port.in.command.LoginCommand;
import com.aireak.identity.application.port.in.dto.AuthResult;

/**
 * Inbound port: authenticate an account and issue an access token + refresh session.
 */
public interface LoginUseCase {
    AuthResult execute(LoginCommand command);
}
