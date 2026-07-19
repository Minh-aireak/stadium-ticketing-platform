package com.aireak.identity.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Value Object wrapping the Account's unique identifier.
 * Uses UUID internally; prevents primitive obsession across the codebase.
 */
public record AccountId(UUID value) {

    public AccountId {
        Objects.requireNonNull(value, "AccountId value must not be null");
    }

    public static AccountId generate() {
        return new AccountId(UUID.randomUUID());
    }

    public static AccountId of(String uuidString) {
        return new AccountId(UUID.fromString(uuidString));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
