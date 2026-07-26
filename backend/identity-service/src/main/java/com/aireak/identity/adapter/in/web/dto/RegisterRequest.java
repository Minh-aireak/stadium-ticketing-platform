package com.aireak.identity.adapter.in.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Inbound DTO for POST /auth/register */
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {}
