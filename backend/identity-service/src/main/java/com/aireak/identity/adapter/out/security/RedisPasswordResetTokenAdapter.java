package com.aireak.identity.adapter.out.security;

import com.aireak.identity.application.port.out.PasswordResetTokenPort;
import com.aireak.identity.config.PasswordResetProperties;
import com.aireak.identity.domain.exception.InvalidPasswordResetTokenException;
import com.aireak.identity.domain.exception.PasswordResetTokenStoreUnavailableException;
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
 * Outbound adapter: Redis-backed one-time password reset tokens implementing
 * {@link PasswordResetTokenPort}.
 *
 * <p>One key per token ({@code auth:reset:password:{sha256(rawToken)}}) holding just the target
 * accountId, with a short TTL. Only the token's hash is ever stored — never the raw value — so a
 * dump of Redis cannot be replayed against the reset endpoint.
 *
 * <p>{@link #peek} reads without deleting and {@link #invalidate} deletes; see
 * {@link PasswordResetTokenPort} for why spending the token is a separate step from resolving it.
 * Mirrors {@link RedisEmailVerificationTokenAdapter}.
 */
@Slf4j
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
    public AccountId peek(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidPasswordResetTokenException("Missing password reset token");
        }

        String accountIdValue;
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(rawToken));
            accountIdValue = bucket.get();
        } catch (RedisException ex) {
            throw new PasswordResetTokenStoreUnavailableException("Failed to read password reset token", ex);
        }

        if (accountIdValue == null) {
            throw new InvalidPasswordResetTokenException("Password reset token is invalid or has expired");
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
            // Swallowed for the same reason as the verification adapter's: the password is already
            // changed and every session revoked by the time this runs, and the key has a TTL. A
            // lingering token can only re-set the password to something the caller must supply and
            // that the account owner has already been told about.
            log.warn("Could not delete a spent password reset token, it will expire on its own: error={}",
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
