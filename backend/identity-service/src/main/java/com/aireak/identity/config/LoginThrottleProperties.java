package com.aireak.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds on {@code LoginAttemptLimiterPort}: how many failures for one email are tolerated
 * ({@code maxFailures}) and how long a failure keeps being counted ({@code windowSeconds}).
 *
 * <p>The window is refreshed by each failure, so an attack that keeps going stays blocked while it
 * keeps going, and the count clears {@code windowSeconds} after it stops. The defaults leave a
 * person who has genuinely forgotten which password they used plenty of room — ten tries — while
 * cutting a distributed guessing attempt on one account down to ten guesses per quarter hour.
 */
@ConfigurationProperties(prefix = "login-throttle")
public record LoginThrottleProperties(int maxFailures, long windowSeconds) {

    // Fails startup rather than misbehaving quietly. A maxFailures of 0 or less would make
    // isThrottled true for an email with no failures at all, refusing every login on the platform,
    // and it would look like a working throttle rather than a misconfiguration. A window of 0
    // would leave the counter without a usable TTL.
    public LoginThrottleProperties {
        if (maxFailures < 1) {
            throw new IllegalArgumentException(
                    "login-throttle.max-failures must be at least 1, was: " + maxFailures);
        }
        if (windowSeconds < 1) {
            throw new IllegalArgumentException(
                    "login-throttle.window-seconds must be at least 1, was: " + windowSeconds);
        }
    }
}
