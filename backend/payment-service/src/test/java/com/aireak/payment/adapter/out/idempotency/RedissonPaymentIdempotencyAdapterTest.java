package com.aireak.payment.adapter.out.idempotency;

import com.aireak.payment.application.port.out.PaymentIdempotencyResult;
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
 * Exercises {@link RedissonPaymentIdempotencyAdapter} against a real Redis (Testcontainers) —
 * the acquire/remember state hinges on Redis's actual NX/TTL semantics, not something worth
 * mocking. Mirrors {@code RedissonIdempotencyStoreTest} in booking-service.
 */
@Testcontainers
class RedissonPaymentIdempotencyAdapterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static RedissonClient redissonClient;
    static RedissonPaymentIdempotencyAdapter adapter;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
        adapter = new RedissonPaymentIdempotencyAdapter(redissonClient);
    }

    @AfterAll
    static void tearDown() {
        redissonClient.shutdown();
    }

    @Test
    void acquiringAFreshKeySucceeds() {
        PaymentIdempotencyResult result = adapter.acquire("fresh-key");

        assertThat(result).isInstanceOf(PaymentIdempotencyResult.Acquired.class);
    }

    @Test
    void acquiringAnAlreadyHeldKeyReportsAlreadyHeld() {
        adapter.acquire("in-flight-key");

        PaymentIdempotencyResult second = adapter.acquire("in-flight-key");

        assertThat(second).isInstanceOf(PaymentIdempotencyResult.AlreadyHeld.class);
    }

    /**
     * Once a key's paymentId is remembered, a subsequent acquire for the same key must be
     * answered from the local (per-JVM) cache without touching Redis at all — proven here by
     * pausing Redis and asserting the second acquire still returns instantly with the correct
     * paymentId instead of blocking on Redis.
     */
    @Test
    void rememberedKeyIsAnsweredFromLocalCacheWithoutHittingRedis() throws Exception {
        adapter.acquire("remembered-key");
        adapter.remember("remembered-key", "payment-456");

        REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "2000");
        try {
            long start = System.currentTimeMillis();

            PaymentIdempotencyResult result = adapter.acquire("remembered-key");

            long elapsedMillis = System.currentTimeMillis() - start;
            assertThat(result).isInstanceOf(PaymentIdempotencyResult.Cached.class);
            assertThat(((PaymentIdempotencyResult.Cached) result).paymentId()).isEqualTo("payment-456");
            assertThat(elapsedMillis).isLessThan(500);
        } finally {
            // Without this, the pause outlives the test (JUnit gives no fixed ordering guarantee)
            // and can bleed into whichever test runs next against this same shared container.
            REDIS.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
        }
    }

    /**
     * A dedicated client with a short {@code timeout} and no retries — connected to the shared
     * {@link #REDIS} container while it's still healthy, then exercised while the container is
     * paused — fails a command fast and deterministically instead of racing Redisson's own
     * (much longer) default retry/backoff against the pause eventually lifting.
     * {@code Redisson.create(...)} itself connects eagerly, so this must connect first and only
     * pause afterwards — pointing at an unreachable address from the start fails at
     * {@code Redisson.create(...)}, before the adapter's own try/catch is ever reached.
     */
    @Test
    void redisFailureFailsOpenToAcquired() throws Exception {
        Config config = new Config();
        config.useSingleServer().setAddress(
                        "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
                .setTimeout(200)
                .setRetryAttempts(0);
        RedissonClient shortTimeoutClient = Redisson.create(config);
        try {
            RedissonPaymentIdempotencyAdapter shortTimeoutAdapter =
                    new RedissonPaymentIdempotencyAdapter(shortTimeoutClient);

            REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "2000");
            try {
                PaymentIdempotencyResult result = shortTimeoutAdapter.acquire("paused-redis-key");

                assertThat(result).isInstanceOf(PaymentIdempotencyResult.Acquired.class);
            } finally {
                REDIS.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
            }
        } finally {
            shortTimeoutClient.shutdown();
        }
    }
}
