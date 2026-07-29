package com.aireak.booking.adapter.out.cache;

import com.aireak.booking.application.port.out.IdempotencyClaim;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link RedissonIdempotencyStore} against a real Redis (Testcontainers) —
 * the claim/complete/release state machine hinges on Redis's actual NX/TTL semantics,
 * not something worth mocking.
 */
@Testcontainers
class RedissonIdempotencyStoreTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static RedissonClient redissonClient;
    static RedissonIdempotencyStore store;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
        store = new RedissonIdempotencyStore(redissonClient);
    }

    @AfterAll
    static void tearDown() {
        redissonClient.shutdown();
    }

    @Test
    void claimingAFreshKeySucceeds() {
        IdempotencyClaim claim = store.claim("fresh-key");

        assertThat(claim).isInstanceOf(IdempotencyClaim.Claimed.class);
    }

    @Test
    void claimingAnAlreadyClaimedKeyReportsInProgress() {
        store.claim("in-flight-key");

        IdempotencyClaim second = store.claim("in-flight-key");

        assertThat(second).isInstanceOf(IdempotencyClaim.InProgress.class);
    }

    @Test
    void completingAClaimMakesSubsequentClaimsReturnTheBookingId() {
        store.claim("completed-key");
        store.complete("completed-key", "booking-123");

        IdempotencyClaim claim = store.claim("completed-key");

        assertThat(claim).isInstanceOf(IdempotencyClaim.Completed.class);
        assertThat(((IdempotencyClaim.Completed) claim).bookingId()).isEqualTo("booking-123");
    }

    @Test
    void releasingAClaimAllowsAFreshClaimAgain() {
        store.claim("released-key");
        store.release("released-key");

        IdempotencyClaim claim = store.claim("released-key");

        assertThat(claim).isInstanceOf(IdempotencyClaim.Claimed.class);
    }

    /**
     * {@code CLIENT PAUSE} makes the server delay responding to every subsequent command for the
     * given duration without closing the connection — a realistic stand-in for a slow/overloaded
     * Redis. Paused well beyond {@code RedissonIdempotencyStore}'s own 300ms claim timeout, so a
     * claim issued during the pause must time out and fail open rather than block on it.
     */
    @Test
    void redisTimeoutFailsOpenToAFreshClaimInsteadOfBlockingOrThrowing() throws Exception {
        REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "2000");
        long start = System.currentTimeMillis();

        IdempotencyClaim claim = store.claim("slow-redis-key");

        long elapsedMillis = System.currentTimeMillis() - start;
        assertThat(claim).isInstanceOf(IdempotencyClaim.Claimed.class);
        assertThat(elapsedMillis).isLessThan(2000);
    }
}
