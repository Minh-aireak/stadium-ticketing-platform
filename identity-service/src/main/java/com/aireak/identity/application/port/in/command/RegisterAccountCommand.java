package com.aireak.identity.application.port.in.command;

/**
 * Command object for account registration.
 * Carries raw (unvalidated) input from the web adapter.
 * Domain validation happens inside the Account aggregate and VO constructors.
 */
public record RegisterAccountCommand(
        String email,
        String rawPassword
) {}
