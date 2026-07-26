package com.aireak.identity.application.port.in.command;

/** Command object for login. */
public record LoginCommand(
        String email,
        String rawPassword
) {}
