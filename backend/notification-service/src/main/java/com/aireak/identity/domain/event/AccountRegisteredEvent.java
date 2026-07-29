package com.aireak.identity.domain.event;

import java.time.Instant;

public record AccountRegisteredEvent(
        AccountId accountId,
        Email email,
        String verificationToken,
        Instant occurredAt
) {
    public record AccountId(String value) {}
    public record Email(String value) {}
}
