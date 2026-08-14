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
        try {
            long start = System.currentTimeMillis();

            IdempotencyClaim claim = store.claim("slow-redis-key");

            long elapsedMillis = System.currentTimeMillis() - start;
            assertThat(claim).isInstanceOf(IdempotencyClaim.Claimed.class);
            assertThat(elapsedMillis).isLessThan(2000);
        } finally {
            // Without this, the pause outlives the test (JUnit gives no fixed ordering guarantee)
            // and can bleed into whichever test runs next against this same shared container.
            REDIS.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
        }
    }

    /**
     * Once a claim is completed, a subsequent claim for the same key must be answered from the
     * local (per-JVM) cache without touching Redis at all — proven here by pausing Redis for
     * longer than {@code CLAIM_TIMEOUT} and asserting the second claim still returns instantly
     * with the correct bookingId instead of timing out and falling open to {@code Claimed}.
     */
    @Test
    void completedClaimIsAnsweredFromLocalCacheWithoutHittingRedis() throws Exception {
        store.claim("locally-cached-key");
        store.complete("locally-cached-key", "booking-456");

        REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "2000");
        try {
            long start = System.currentTimeMillis();

            IdempotencyClaim claim = store.claim("locally-cached-key");

            long elapsedMillis = System.currentTimeMillis() - start;
            assertThat(claim).isInstanceOf(IdempotencyClaim.Completed.class);
            assertThat(((IdempotencyClaim.Completed) claim).bookingId()).isEqualTo("booking-456");
            assertThat(elapsedMillis).isLessThan(300);
        } finally {
            // Without this, the pause outlives the test (JUnit gives no fixed ordering guarantee)
            // and can bleed into whichever test runs next against this same shared container.
            REDIS.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
        }
    }

    /**
     * Two {@link RedissonIdempotencyStore} instances sharing the same Redis (simulating two pods,
     * each with its own local cache) must never let a stale local cache paper over the real
     * distributed state: pod B's local cache has nothing cached for this key when it first sees
     * InProgress, and must reflect pod A's completion once it re-checks — proving InProgress is
     * never cached locally (only COMPLETED is), so it can never mask a completion that already
     * happened elsewhere.
     */
    @Test
    void inProgressIsNeverCachedSoADifferentInstanceSeesCompletionAfterOriginalClaimant() {
        RedissonIdempotencyStore podA = new RedissonIdempotencyStore(redissonClient);
        RedissonIdempotencyStore podB = new RedissonIdempotencyStore(redissonClient);

        IdempotencyClaim podAClaim = podA.claim("cross-pod-key");
        assertThat(podAClaim).isInstanceOf(IdempotencyClaim.Claimed.class);

        IdempotencyClaim podBClaim = podB.claim("cross-pod-key");
        assertThat(podBClaim).isInstanceOf(IdempotencyClaim.InProgress.class);

        podA.complete("cross-pod-key", "booking-789");

        IdempotencyClaim podBClaimAfterCompletion = podB.claim("cross-pod-key");
        assertThat(podBClaimAfterCompletion).isInstanceOf(IdempotencyClaim.Completed.class);
        assertThat(((IdempotencyClaim.Completed) podBClaimAfterCompletion).bookingId()).isEqualTo("booking-789");
    }
}
