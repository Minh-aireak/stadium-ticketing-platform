package com.aireak.identity.domain.event;

import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.Email;

import java.time.Instant;

/**
 * Domain event: raised when an Account transitions PENDING_VERIFICATION → ACTIVE.
 */
public record AccountActivatedEvent(
        AccountId accountId,
        Email email,
        Instant occurredAt
) {
    public AccountActivatedEvent(AccountId accountId, Email email) {
        this(accountId, email, Instant.now());
    }
}
