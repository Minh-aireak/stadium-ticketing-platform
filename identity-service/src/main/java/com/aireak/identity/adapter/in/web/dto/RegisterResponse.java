package com.aireak.identity.adapter.in.web.dto;

/** Outbound DTO for successful registration */
public record RegisterResponse(String accountId, String message) {
    public static RegisterResponse of(String accountId) {
        return new RegisterResponse(accountId, "Registration successful. Please verify your email.");
    }
}
