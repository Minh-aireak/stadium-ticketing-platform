package com.aireak.identity.application.port.in.command;

public record ResetPasswordCommand(String token, String newPassword) {}
