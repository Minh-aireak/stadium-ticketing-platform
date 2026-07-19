package com.aireak.identity.adapter.in.web.dto;

/** Outbound DTO for successful login */
public record LoginResponse(String token, String tokenType) {
    public static LoginResponse bearer(String token) {
        return new LoginResponse(token, "Bearer");
    }
}
