package com.aireak.identity.application.port.in;

/**
 * Inbound port: "I forgot my password".
 *
 * <p>Always completes normally, whether or not the address belongs to an account — the response
 * must not tell an attacker which emails are registered.
 */
public interface RequestPasswordResetUseCase {
    void execute(String email);
}
