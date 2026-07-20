package com.aireak.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPolicyTest {

    @Test
    void retryAfterIsRequestedTokensOverReplenishRateRoundedUp() {
        // replenishRate=1, requestedTokens=12 -> 12s to refill enough for one more login attempt
        assertThat(RateLimitPolicy.LOGIN.retryAfterSeconds()).isEqualTo(12);
        // replenishRate=1, requestedTokens=6
        assertThat(RateLimitPolicy.PAYMENT.retryAfterSeconds()).isEqualTo(6);
        // replenishRate=1, requestedTokens=1
        assertThat(RateLimitPolicy.NOTIFICATION.retryAfterSeconds()).isEqualTo(1);
        // replenishRate=10, requestedTokens=3 -> ceil(0.3) = 1
        assertThat(RateLimitPolicy.READ_AUTHENTICATED.retryAfterSeconds()).isEqualTo(1);
        // replenishRate=1, requestedTokens=1200
        assertThat(RateLimitPolicy.REGISTER.retryAfterSeconds()).isEqualTo(1200);
    }

    @Test
    void keyStrategiesMatchTheBaselineTable() {
        assertThat(RateLimitPolicy.LOGIN.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.IP);
        assertThat(RateLimitPolicy.REGISTER.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.IP);
        assertThat(RateLimitPolicy.REFRESH_TOKEN.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.USER_OR_IP);
        assertThat(RateLimitPolicy.READ_ANONYMOUS.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.IP);
        assertThat(RateLimitPolicy.READ_AUTHENTICATED.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.USER);
        assertThat(RateLimitPolicy.BOOKING.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.USER);
        assertThat(RateLimitPolicy.PAYMENT.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.USER);
        assertThat(RateLimitPolicy.NOTIFICATION.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.USER);
        assertThat(RateLimitPolicy.DEFAULT_AUTHENTICATED.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.USER);
        assertThat(RateLimitPolicy.DEFAULT_ANONYMOUS.keyStrategy()).isEqualTo(RateLimitPolicy.KeyStrategy.IP);
    }
}
