package com.aireak.identity.application.port.in;

/**
 * Inbound port: activate an account via its email verification token.
 * Driving adapter (AuthController) calls this.
 */
public interface VerifyEmailUseCase {
    void execute(String rawToken);
}
