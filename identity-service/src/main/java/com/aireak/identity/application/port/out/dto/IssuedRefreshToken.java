package com.aireak.identity.application.port.out.dto;

import java.time.Instant;

/**
 * Result of creating or rotating a refresh session.
 *
 * @param rawToken opaque token to hand to the client — never persisted in plaintext, never logged
 */
public record IssuedRefreshToken(String sessionId, String familyId, String accountId, String rawToken, Instant expiresAt) {
}
