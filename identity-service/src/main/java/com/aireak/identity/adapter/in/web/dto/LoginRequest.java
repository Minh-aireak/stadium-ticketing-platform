package com.aireak.identity.adapter.in.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Inbound DTO for POST /auth/login */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {}
