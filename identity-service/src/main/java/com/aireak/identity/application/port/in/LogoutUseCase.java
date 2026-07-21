package com.aireak.identity.application.port.in;

/** Inbound port: revoke the refresh session (token family) tied to the presented refresh token. */
public interface LogoutUseCase {
    void execute(String rawRefreshToken);
}
