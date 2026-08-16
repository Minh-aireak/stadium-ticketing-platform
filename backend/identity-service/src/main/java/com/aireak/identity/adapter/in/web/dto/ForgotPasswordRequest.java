package com.aireak.identity.adapter.in.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Inbound DTO for POST /auth/forgot-password */
public record ForgotPasswordRequest(
        @NotBlank @Email String email
) {}
