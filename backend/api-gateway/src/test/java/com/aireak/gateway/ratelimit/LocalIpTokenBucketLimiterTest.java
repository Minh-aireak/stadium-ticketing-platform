package com.aireak.gateway.ratelimit;

import com.google.common.base.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two properties that make a local bucket safe to swap in for the Redis one: it still has to
 * enforce the policy's numbers, and it must not grow without bound just because an attacker
 * chooses the keys.
 */
class LocalIpTokenBucketLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return nanos.get();
        }
    };

    @Test
    void enforcesTheFullPolicyAllowanceOnASingleInstance() {
        LocalIpTokenBucketLimiter limiter =
                new LocalIpTokenBucketLimiter(RateLimitPolicy.PRE_AUTH_IP, 1, 1_000, ticker);

        assertThat(limiter.replenishRate()).isEqualTo(20);
        assertThat(limiter.burstCapacity()).isEqualTo(600);
        // 600 capacity / 3 tokens per request
        for (int i = 0; i < 200; i++) {
            assertThat(limiter.tryConsume("ip:203.0.113.1").allowed()).isTrue();
        }
        assertThat(limiter.tryConsume("ip:203.0.113.1").allowed()).isFalse();
    }

    @Test
    void splitsTheAllowanceAcrossInstancesSoTheClusterTotalStaysPut() {
        LocalIpTokenBucketLimiter limiter =
                new LocalIpTokenBucketLimiter(RateLimitPolicy.PRE_AUTH_IP, 4, 1_000, ticker);

        assertThat(limiter.replenishRate()).isEqualTo(5);
        assertThat(limiter.burstCapacity()).isEqualTo(150);
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryConsume("ip:203.0.113.2").allowed()).isTrue();
        }
        assertThat(limiter.tryConsume("ip:203.0.113.2").allowed()).isFalse();
    }

    @Test
    void refillsAtTheReplenishRateAndStopsAtBurstCapacity() {
        LocalIpTokenBucketLimiter limiter =
                new LocalIpTokenBucketLimiter(RateLimitPolicy.PRE_AUTH_IP, 1, 1_000, ticker);
        for (int i = 0; i < 200; i++) {
            limiter.tryConsume("ip:203.0.113.3");
        }

        // 20 tokens/s buys 6 requests of 3 tokens after a second, and no more.
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryConsume("ip:203.0.113.3").allowed()).isTrue();
        }
        assertThat(limiter.tryConsume("ip:203.0.113.3").allowed()).isFalse();

        // Idle far longer than a full refill still leaves the bucket capped at burstCapacity.
        nanos.addAndGet(Duration.ofHours(1).toNanos());
        for (int i = 0; i < 200; i++) {
            assertThat(limiter.tryConsume("ip:203.0.113.3").allowed()).isTrue();
        }
        assertThat(limiter.tryConsume("ip:203.0.113.3").allowed()).isFalse();
    }

    /** A per-IP map an attacker fills is a memory leak unless it is capped — so cap it. */
    @Test
    void staysBoundedWhenScannedFromFarMoreIpsThanItTracks() {
        LocalIpTokenBucketLimiter limiter =
                new LocalIpTokenBucketLimiter(RateLimitPolicy.PRE_AUTH_IP, 1, 100, ticker);

        for (int i = 0; i < 10_000; i++) {
            limiter.tryConsume("ip:198.51.100." + i);
        }

        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(100);
    }

    @Test
    void dropsBucketsThatHaveBeenIdleLongEnoughToHaveRefilledCompletely() {
        LocalIpTokenBucketLimiter limiter =
                new LocalIpTokenBucketLimiter(RateLimitPolicy.PRE_AUTH_IP, 1, 1_000, ticker);
        limiter.tryConsume("ip:203.0.113.4");
        assertThat(limiter.trackedKeys()).isEqualTo(1);

        // 600 capacity / 20 per second = 30s to refill from empty; past that the entry says
        // nothing a fresh bucket would not.
        nanos.addAndGet(Duration.ofSeconds(31).toNanos());
        limiter.tryConsume("ip:203.0.113.5");

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }
}
