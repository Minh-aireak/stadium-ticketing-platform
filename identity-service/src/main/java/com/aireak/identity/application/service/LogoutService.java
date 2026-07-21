package com.aireak.identity.application.service;

import com.aireak.identity.application.port.in.LogoutUseCase;
import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Application service: revokes the refresh session tied to the presented refresh token.
 * Idempotent — logging out twice (or with a stale/absent token) is not an error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutService implements LogoutUseCase {

    private final RefreshSessionStorePort refreshSessionStorePort;

    @Override
    public void execute(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshSessionStorePort.revokeFamily(rawRefreshToken);
        log.info("Logout: refresh session revoked");
    }
}
