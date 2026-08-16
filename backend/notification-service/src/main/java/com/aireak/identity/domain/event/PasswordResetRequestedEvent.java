package com.aireak.identity.domain.event;

import java.time.Instant;

/**
 * Local copy of identity-service's domain event — same package and shape so {@code EventEnvelope}'s
 * {@code @JsonTypeInfo(use = Id.CLASS)} payload deserialization resolves it by fully-qualified
 * class name (see the sibling {@link AccountRegisteredEvent} for the same arrangement).
 *
 * <p>Carries the raw, one-time reset token; notification-service turns it into the customer-facing
 * link because only it knows the browser-reachable frontend base URL — identity-service is not
 * required to know where its own users' UI lives.
 */
public record PasswordResetRequestedEvent(
        AccountId accountId,
        Email email,
        String resetToken,
        long expiresInMinutes,
        Instant occurredAt
) {
    public record AccountId(String value) {}
    public record Email(String value) {}
}
