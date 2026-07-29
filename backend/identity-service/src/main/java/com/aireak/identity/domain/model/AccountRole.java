package com.aireak.identity.domain.model;

/**
 * Authorization role of an Account. Carried as a claim in the access token (see
 * {@code JwtTokenGeneratorAdapter}) so downstream services can enforce role checks without
 * calling back into identity-service.
 */
public enum AccountRole {
    USER,
    ADMIN
}
