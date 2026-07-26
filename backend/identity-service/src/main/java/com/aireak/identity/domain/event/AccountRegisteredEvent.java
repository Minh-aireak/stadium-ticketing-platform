package com.aireak.identity.domain.event;

import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.Email;

import java.time.Instant;

/**
 * Domain event: raised when a new Account is successfully registered.
 * Published to Kafka → notification-service sends welcome/verification email.
 */
public record AccountRegisteredEvent(
        AccountId accountId,
        Email email,
        Instant occurredAt
) {
    public AccountRegisteredEvent(AccountId accountId, Email email) {
        this(accountId, email, Instant.now());
    }
}
