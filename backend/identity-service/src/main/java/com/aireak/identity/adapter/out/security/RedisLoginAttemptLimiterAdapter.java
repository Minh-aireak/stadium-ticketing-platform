package com.aireak.identity.adapter.out.security;

import com.aireak.identity.application.port.out.LoginAttemptLimiterPort;
import com.aireak.identity.config.LoginThrottleProperties;
import com.aireak.identity.domain.model.Email;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Outbound adapter: Redis-backed failed-login counter implementing {@link LoginAttemptLimiterPort}.
 *
 * <p>One key per email ({@code auth:login:failures:{sha256(email)}}) holding a count, with the TTL
 * pushed forward by every failure. Redis rather than memory because identity-service is not
 * guaranteed to be a single instance, and a counter each replica keeps to itself would divide the
 * threshold by the number of replicas.
 *
 * <p>Only the hash of the address is stored, like {@link RedisPasswordResetTokenAdapter} does with
 * its tokens: this key exists purely to be counted against, and it can do that without Redis
 * holding a list of the addresses people have tried to log in with. The {@link Email} value object
 * has already trimmed and lower-cased the address, so the hash is stable across attempts.
 *
 * <p><strong>Fails open.</strong> Every method swallows {@link RedisException} and logs, so a Redis
 * outage costs the platform this defence and nothing else. Failing closed would turn an outage of
 * the throttle into an outage of login for everyone, which is a far worse trade than the one this
 * exists to make — and the gateway's per-IP limit is unaffected either way.
 *
 * <p>Worth being clear about what this does not do: because the window is refreshed on each
 * failure, an attacker willing to keep sending one request per window can keep a specific account
 * throttled. That is the residual cost of throttling something the caller chooses. It is bounded —
 * they must keep spending requests against the gateway's per-IP limit to hold it, and the account
 * itself is never disabled, so the moment they stop, the owner can log in again.
 */
@Component
public class RedisLoginAttemptLimiterAdapter implements LoginAttemptLimiterPort {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginAttemptLimiterAdapter.class);

    private static final String KEY_PREFIX = "auth:login:failures:";

    private final RedissonClient redissonClient;
    private final LoginThrottleProperties properties;

    public RedisLoginAttemptLimiterAdapter(RedissonClient redissonClient,
                                            LoginThrottleProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @Override
    public boolean isThrottled(Email email) {
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(key(email));
            return counter.get() >= properties.maxFailures();
        } catch (RedisException ex) {
            log.warn("Login throttle unavailable, allowing attempt: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public void recordFailure(Email email) {
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(key(email));
            counter.incrementAndGet();
            // After the increment, and on every failure rather than only the first: the key must
            // never be left without one. incrementAndGet on a missing key creates it with no TTL,
            // so a failure that raced with the key expiring would otherwise leave a count behind
            // permanently — and a permanent count at the threshold is the account lock this is
            // meant not to be.
            counter.expire(Duration.ofSeconds(properties.windowSeconds()));
        } catch (RedisException ex) {
            log.warn("Login throttle unavailable, failure not counted: {}", ex.getMessage());
        }
    }

    @Override
    public void reset(Email email) {
        try {
            redissonClient.getAtomicLong(key(email)).delete();
        } catch (RedisException ex) {
            log.warn("Login throttle unavailable, count not cleared: {}", ex.getMessage());
        }
    }

    private String key(Email email) {
        return KEY_PREFIX + sha256Hex(email.value());
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
