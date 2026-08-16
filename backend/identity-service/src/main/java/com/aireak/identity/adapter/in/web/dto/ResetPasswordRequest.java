package com.aireak.identity.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Inbound DTO for POST /auth/reset-password. No length/complexity constraints here — the policy
 * lives in {@code RawPassword} so registration and reset can never drift apart.
 */
public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank String newPassword
) {}
