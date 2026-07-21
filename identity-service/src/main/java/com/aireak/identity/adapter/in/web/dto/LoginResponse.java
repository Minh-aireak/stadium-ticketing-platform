package com.aireak.identity.adapter.in.web.dto;

/** Outbound DTO for a successful login or refresh. The refresh token travels only via cookie. */
public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
    public static LoginResponse bearer(String accessToken, long expiresInSeconds) {
        return new LoginResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
