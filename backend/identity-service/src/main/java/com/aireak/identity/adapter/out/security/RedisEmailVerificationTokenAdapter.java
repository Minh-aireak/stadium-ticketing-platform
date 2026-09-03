package com.aireak.identity.adapter.out.security;

import com.aireak.identity.application.port.out.EmailVerificationTokenPort;
import com.aireak.identity.config.EmailVerificationProperties;
import com.aireak.identity.domain.exception.InvalidVerificationTokenException;
import com.aireak.identity.domain.exception.VerificationTokenStoreUnavailableException;
import com.aireak.identity.domain.model.AccountId;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Outbound adapter: Redis-backed one-time email verification tokens implementing
 * {@link EmailVerificationTokenPort}.
 *
 * <p>One key per token ({@code auth:verify:email:{sha256(rawToken)}}) holding just the target
 * accountId, with a TTL. Only the token's hash is ever stored — never the raw value — mirroring
 * {@code RedisRefreshSessionAdapter}.
 *
 * <p>{@link #peek} reads without deleting and {@link #invalidate} deletes; see
 * {@link EmailVerificationTokenPort} for why spending the token is a separate step from resolving
 * it. This used to be one atomic get-and-delete, which spent the token before the activation it
 * authorised had committed.
 */
@Slf4j
@Component
public class RedisEmailVerificationTokenAdapter implements EmailVerificationTokenPort {

    private static final String KEY_PREFIX = "auth:verify:email:";
    private static final int TOKEN_BYTES = 32;

    private final RedissonClient redissonClient;
    private final EmailVerificationProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RedisEmailVerificationTokenAdapter(RedissonClient redissonClient, EmailVerificationProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @Override
    public String generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    @Override
    public void store(String rawToken, AccountId accountId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(rawToken));
            bucket.set(accountId.toString(), Duration.ofSeconds(properties.ttlSeconds()));
        } catch (RedisException ex) {
            throw new VerificationTokenStoreUnavailableException("Failed to store verification token", ex);
        }
    }

    @Override
    public AccountId peek(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidVerificationTokenException("Missing verification token");
        }

        String accountIdValue;
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(rawToken));
            accountIdValue = bucket.get();
        } catch (RedisException ex) {
            throw new VerificationTokenStoreUnavailableException("Failed to read verification token", ex);
        }

        if (accountIdValue == null) {
            throw new InvalidVerificationTokenException("Verification token is invalid or has expired");
        }
        return AccountId.of(accountIdValue);
    }

    @Override
    public void invalidate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        try {
            redissonClient.getBucket(key(rawToken)).delete();
        } catch (RedisException ex) {
            // Deliberately swallowed. The account is already ACTIVE by the time this runs, and the
            // key carries a TTL, so the worst outcome is a spent token that lingers until it
            // expires -- where a repeat click meets an activation that is already a no-op. Throwing
            // would turn a completed verification into an error page for the customer.
            log.warn("Could not delete a spent verification token, it will expire on its own: error={}",
                    ex.getMessage());
        }
    }

    private String key(String rawToken) {
        return KEY_PREFIX + sha256Hex(rawToken);
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
