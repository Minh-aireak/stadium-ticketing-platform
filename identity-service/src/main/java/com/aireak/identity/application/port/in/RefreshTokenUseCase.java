package com.aireak.identity.application.port.in;

import com.aireak.identity.application.port.in.dto.AuthResult;

/**
 * Inbound port: rotate a refresh token and issue a new access token.
 *
 * @see com.aireak.identity.application.port.out.RefreshSessionStorePort#rotate(String)
 */
public interface RefreshTokenUseCase {
    AuthResult execute(String rawRefreshToken);
}
