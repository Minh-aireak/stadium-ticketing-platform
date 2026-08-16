package com.aireak.identity.adapter.out.security;

import com.aireak.identity.application.port.out.PasswordResetTokenPort;
import com.aireak.identity.config.PasswordResetProperties;
import com.aireak.identity.domain.exception.InvalidPasswordResetTokenException;
import com.aireak.identity.domain.exception.PasswordResetTokenStoreUnavailableException;
import com.aireak.identity.domain.model.AccountId;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Outbound adapter: Redis-backed one-time password reset tokens implementing
 * {@link PasswordResetTokenPort}.
 *
 * <p>One key per token ({@code auth:reset:password:{sha256(rawToken)}}) holding just the target
 * accountId, with a short TTL. Only the token's hash is ever stored — never the raw value — so a
 * dump of Redis cannot be replayed against the reset endpoint. {@link #consume} uses Redisson's
 * atomic get-and-delete so a token can change a password at most once, even under concurrent
 * requests. Mirrors {@link RedisEmailVerificationTokenAdapter}.
 */
@Component
public class RedisPasswordResetTokenAdapter implements PasswordResetTokenPort {

    private static final String KEY_PREFIX = "auth:reset:password:";
    private static final int TOKEN_BYTES = 32;

    private final RedissonClient redissonClient;
    private final PasswordResetProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RedisPasswordResetTokenAdapter(RedissonClient redissonClient, PasswordResetProperties properties) {
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
            throw new PasswordResetTokenStoreUnavailableException("Failed to store password reset token", ex);
        }
    }

    @Override
    public AccountId consume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidPasswordResetTokenException("Missing password reset token");
        }

        String accountIdValue;
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(rawToken));
            accountIdValue = bucket.getAndDelete();
        } catch (RedisException ex) {
            throw new PasswordResetTokenStoreUnavailableException("Failed to read password reset token", ex);
        }

        if (accountIdValue == null) {
            throw new InvalidPasswordResetTokenException("Password reset token is invalid or has expired");
        }
        return AccountId.of(accountIdValue);
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
