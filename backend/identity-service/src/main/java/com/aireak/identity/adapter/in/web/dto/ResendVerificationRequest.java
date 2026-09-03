package com.aireak.identity.adapter.in.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Inbound DTO for POST /auth/resend-verification */
public record ResendVerificationRequest(
        @NotBlank @Email String email
) {}
