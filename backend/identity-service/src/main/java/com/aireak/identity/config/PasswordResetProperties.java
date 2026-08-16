package com.aireak.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ttlSeconds} is deliberately much shorter than email verification's: a reset link is a
 * live credential for an existing account, so it expires in minutes, not a day.
 */
@ConfigurationProperties(prefix = "password-reset")
public record PasswordResetProperties(long ttlSeconds) {

    public long ttlMinutes() {
        return ttlSeconds / 60;
    }
}
