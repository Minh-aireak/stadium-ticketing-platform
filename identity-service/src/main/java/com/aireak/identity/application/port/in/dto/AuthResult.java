package com.aireak.identity.application.port.in.dto;

import com.aireak.identity.application.port.out.dto.IssuedRefreshToken;

import java.time.Instant;

/** Result of a successful login or refresh: a fresh access token paired with a rotated refresh session. */
public record AuthResult(String accessToken, Instant accessTokenExpiresAt, IssuedRefreshToken refreshToken) {
}
