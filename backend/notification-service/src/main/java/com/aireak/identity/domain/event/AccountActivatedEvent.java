package com.aireak.identity.domain.event;

import java.time.Instant;

public record AccountActivatedEvent(
        AccountId accountId,
        Email email,
        Instant occurredAt
) {
    public record AccountId(String value) {}
    public record Email(String value) {}
}
