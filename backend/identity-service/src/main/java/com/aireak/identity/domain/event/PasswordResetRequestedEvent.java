package com.aireak.identity.domain.event;

import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.Email;

import java.time.Instant;

/**
 * Domain event: raised when a customer asks to reset their password.
 * Published to Kafka → notification-service sends the reset-link email.
 *
 * <p>Carries the raw token, not a URL: only notification-service knows the browser-reachable
 * frontend base URL. {@code expiresInMinutes} travels with the event so the email can state the
 * validity window without notification-service duplicating identity-service's TTL config.
 */
public record PasswordResetRequestedEvent(
        AccountId accountId,
        Email email,
        String resetToken,
        long expiresInMinutes,
        Instant occurredAt
) {
    public PasswordResetRequestedEvent(AccountId accountId, Email email, String resetToken, long expiresInMinutes) {
        this(accountId, email, resetToken, expiresInMinutes, Instant.now());
    }
}
