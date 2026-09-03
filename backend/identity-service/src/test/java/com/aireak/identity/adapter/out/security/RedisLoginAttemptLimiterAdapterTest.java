package com.aireak.identity.adapter.out.security;

import com.aireak.identity.config.LoginThrottleProperties;
import com.aireak.identity.domain.model.Email;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link RedisLoginAttemptLimiterAdapter} against a real Redis (Testcontainers). The
 * behaviour that matters here is Redis's own — that the counter survives across calls, that the
 * key carries a TTL so the throttle can never become a permanent lock, and that deleting it
 * restores a clean slate — none of which a mocked RedissonClient would say anything about.
 */
@Testcontainers
class RedisLoginAttemptLimiterAdapterTest {

    private static final int MAX_FAILURES = 3;
    private static final long WINDOW_SECONDS = 900;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static RedissonClient redissonClient;
    static RedisLoginAttemptLimiterAdapter adapter;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
        adapter = new RedisLoginAttemptLimiterAdapter(redissonClient,
                new LoginThrottleProperties(MAX_FAILURES, WINDOW_SECONDS));
    }

    @AfterAll
    static void tearDown() {
        redissonClient.shutdown();
    }

    /** A fresh address per test: these share one Redis, and the counter is the thing under test. */
    private static Email freshEmail() {
        return new Email("user-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void anEmailWithNoRecentFailuresIsNotThrottled() {
        assertThat(adapter.isThrottled(freshEmail())).isFalse();
    }

    @Test
    void failuresBelowTheThresholdDoNotThrottle() {
        Email email = freshEmail();

        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            adapter.recordFailure(email);
        }

        assertThat(adapter.isThrottled(email)).isFalse();
    }

    @Test
    void reachingTheThresholdThrottles() {
        Email email = freshEmail();

        for (int i = 0; i < MAX_FAILURES; i++) {
            adapter.recordFailure(email);
        }

        assertThat(adapter.isThrottled(email)).isTrue();
    }

    @Test
    void theCounterAlwaysCarriesATtlSoTheThrottleCannotBecomePermanent() {
        Email email = freshEmail();

        // Checked after every failure, not just the first. incrementAndGet on a missing key
        // creates it without a TTL, so a failure that raced with the key expiring could otherwise
        // leave a count that never goes away — an account lock by accident, which is the one
        // outcome this design rules out.
        for (int i = 0; i < MAX_FAILURES; i++) {
            adapter.recordFailure(email);

            long remaining = redissonClient.getAtomicLong(
                    "auth:login:failures:" + sha256Hex(email.value())).remainTimeToLive();
            assertThat(remaining).isGreaterThan(0).isLessThanOrEqualTo(WINDOW_SECONDS * 1000);
        }
    }

    @Test
    void aResetClearsTheCountSoTheNextAttemptStartsFromScratch() {
        Email email = freshEmail();
        for (int i = 0; i < MAX_FAILURES; i++) {
            adapter.recordFailure(email);
        }
        assertThat(adapter.isThrottled(email)).isTrue();

        adapter.reset(email);

        assertThat(adapter.isThrottled(email)).isFalse();
    }

    @Test
    void countsAreKeptPerEmailNotGlobally() {
        Email throttled = freshEmail();
        Email bystander = freshEmail();
        for (int i = 0; i < MAX_FAILURES; i++) {
            adapter.recordFailure(throttled);
        }

        assertThat(adapter.isThrottled(throttled)).isTrue();
        assertThat(adapter.isThrottled(bystander)).isFalse();
    }

    @Test
    void theSameAddressIsCountedRegardlessOfCaseOrSurroundingSpace() {
        // Email normalises on construction, so these are one account's worth of failures and must
        // land on one key — otherwise the throttle is sidestepped by varying the capitalisation.
        Email plain = freshEmail();
        for (int i = 0; i < MAX_FAILURES; i++) {
            adapter.recordFailure(new Email("  " + plain.value().toUpperCase() + " "));
        }

        assertThat(adapter.isThrottled(plain)).isTrue();
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
