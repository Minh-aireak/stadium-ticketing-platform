package com.aireak.identity.adapter.out.security;

import com.aireak.identity.application.port.out.RefreshSessionStorePort;
import com.aireak.identity.application.port.out.dto.IssuedRefreshToken;
import com.aireak.identity.config.RefreshTokenProperties;
import com.aireak.identity.domain.exception.InvalidRefreshTokenException;
import com.aireak.identity.domain.exception.RefreshSessionStoreUnavailableException;
import com.aireak.identity.domain.exception.RefreshTokenReuseException;
import com.aireak.identity.domain.model.AccountId;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Outbound adapter: Redis-backed refresh session store implementing {@link RefreshSessionStorePort}.
 *
 * <p>Design: one Redis key per login ({@code auth:refresh:session:{sessionId}}), a Hash holding
 * only the CURRENT valid token's hash plus session metadata. The opaque token handed to the
 * client encodes {@code sessionId || secret}; the server never stores the secret, only
 * {@code sha256(secret)}.
 *
 * <p>Rotation and reuse detection run as a single Lua script (EVAL), which Redis executes
 * atomically — this is what makes concurrent refresh requests race-safe: only one caller can
 * ever observe a matching {@code tokenHash} and win the rotation; every other caller (whether an
 * attacker replaying a stolen token, or a genuine duplicate request) sees a mismatch against the
 * already-rotated hash and is treated as reuse, which revokes the whole session.
 *
 * <p>A second key per account ({@code auth:refresh:account:{accountId}}, a Set of that account's
 * session ids) exists solely so {@link #revokeAllForAccount} can find sessions it holds no token
 * for. It is an index, not a source of truth: entries are never pruned on rotation or expiry, so
 * every read re-checks each session key still exists. Both scripts that span the index and a
 * session key assume a single-node Redis (what this platform deploys); on a cluster the two keys
 * would need a hash tag to share a slot.
 */
@Slf4j
@Component
public class RedisRefreshSessionAdapter implements RefreshSessionStorePort {

    private static final String KEY_PREFIX = "auth:refresh:session:";
    private static final String ACCOUNT_INDEX_PREFIX = "auth:refresh:account:";
    private static final int SECRET_BYTES = 32;
    private static final int SESSION_ID_BYTES = 16;

    // Returns "OK:{expiresAt}:{userId}" on success, carrying the two fields the caller needs back
    // in the same atomic step that rotated the token. A second getMap(...).get("expiresAt") round
    // trip used to fetch them, which both cost an extra command on the refresh hot path and could
    // observe the session key already gone (its TTL elapsing between the two calls) — the caller
    // then did Long.parseLong(null) and answered a 500 where a 401 was correct.
    private static final String ROTATE_SCRIPT = """
            local d = redis.call('HGETALL', KEYS[1])
            if #d == 0 then
              return 'NOT_FOUND'
            end
            local m = {}
            for i = 1, #d, 2 do m[d[i]] = d[i + 1] end

            if m['revokedAt'] ~= '' then
              return 'REVOKED'
            end
            if tonumber(m['expiresAt']) <= tonumber(ARGV[2]) then
              return 'EXPIRED'
            end
            if m['tokenHash'] ~= ARGV[1] then
              redis.call('HSET', KEYS[1], 'revokedAt', ARGV[2])
              return 'REUSE_DETECTED'
            end

            redis.call('HSET', KEYS[1], 'tokenHash', ARGV[3], 'lastUsedAt', ARGV[2])
            return 'OK:' .. m['expiresAt'] .. ':' .. m['userId']
            """;

    // Revokes only on a tokenHash match, the same proof of possession ROTATE_SCRIPT demands.
    // Without it, holding any 48-byte string whose first 16 bytes are a valid sessionId was enough
    // to revoke that session — the secret half was never checked. Guessing a random UUIDv4 session
    // id makes that impractical rather than impossible, and "impractical" is not the guarantee to
    // rest a logout endpoint on. A mismatch is reported as success: logout is idempotent from the
    // client's point of view and must not become an oracle for which session ids exist.
    private static final String REVOKE_SCRIPT = """
            local d = redis.call('HGETALL', KEYS[1])
            if #d == 0 then
              return 'OK'
            end
            local m = {}
            for i = 1, #d, 2 do m[d[i]] = d[i + 1] end

            if m['tokenHash'] == ARGV[2] then
              redis.call('HSET', KEYS[1], 'revokedAt', ARGV[1])
            end
            return 'OK'
            """;

    // HSET + PEXPIRE in one atomic EVAL so a crash/disconnect between the two Redis calls can
    // never leave a session hash with no TTL (HSET alone doesn't carry one). The account index
    // (KEYS[2]) is written in the same EVAL for the same reason — a session missing from it would
    // survive revokeAllForAccount invisibly. Its TTL is pushed out to match the newest session, so
    // the index never outlives the last session it points at.
    private static final String CREATE_SCRIPT = """
            redis.call('HSET', KEYS[1],
              'userId', ARGV[1],
              'tokenHash', ARGV[2],
              'tokenFamilyId', ARGV[3],
              'createdAt', ARGV[4],
              'expiresAt', ARGV[5],
              'revokedAt', '',
              'lastUsedAt', ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            redis.call('SADD', KEYS[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[6])
            return 'OK'
            """;

    // Marks every still-live session in the account's index as revoked, then drops the index.
    // Session keys are built inside the script from ARGV[2] rather than passed as KEYS because
    // their number isn't known to the caller; see the single-node note in the class javadoc.
    private static final String REVOKE_ALL_SCRIPT = """
            local ids = redis.call('SMEMBERS', KEYS[1])
            local revoked = 0
            for i = 1, #ids do
              local sessionKey = ARGV[2] .. ids[i]
              if redis.call('EXISTS', sessionKey) == 1 then
                redis.call('HSET', sessionKey, 'revokedAt', ARGV[1])
                revoked = revoked + 1
              end
            end
            redis.call('DEL', KEYS[1])
            return tostring(revoked)
            """;

    private final RedissonClient redissonClient;
    private final RefreshTokenProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RedisRefreshSessionAdapter(RedissonClient redissonClient, RefreshTokenProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @Override
    public IssuedRefreshToken createSession(AccountId accountId) {
        UUID sessionId = UUID.randomUUID();
        byte[] secret = randomSecret();
        String tokenHash = sha256Hex(secret);

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.absoluteTtlSeconds());
        long ttlMillis = Duration.between(now, expiresAt).toMillis();

        try {
            redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    CREATE_SCRIPT,
                    RScript.ReturnType.VALUE,
                    List.of(sessionKey(sessionId), accountIndexKey(accountId)),
                    accountId.toString(), tokenHash, sessionId.toString(),
                    String.valueOf(now.toEpochMilli()), String.valueOf(expiresAt.toEpochMilli()),
                    String.valueOf(ttlMillis));
        } catch (RedisException ex) {
            throw new RefreshSessionStoreUnavailableException("Failed to create refresh session", ex);
        }

        return new IssuedRefreshToken(sessionId.toString(), sessionId.toString(), accountId.toString(),
                encodeToken(sessionId, secret), expiresAt);
    }

    @Override
    public IssuedRefreshToken rotate(String rawToken) {
        DecodedToken decoded = decode(rawToken);
        byte[] newSecret = randomSecret();
        String newTokenHash = sha256Hex(newSecret);
        long now = Instant.now().toEpochMilli();

        String status;
        try {
            status = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    ROTATE_SCRIPT,
                    RScript.ReturnType.VALUE,
                    List.of(sessionKey(decoded.sessionId())),
                    decoded.tokenHash(), String.valueOf(now), newTokenHash);
        } catch (RedisException ex) {
            throw new RefreshSessionStoreUnavailableException("Failed to rotate refresh token", ex);
        }

        if (status != null && status.startsWith("OK:")) {
            // "OK:{expiresAt}:{userId}" — split on the first two separators only, since a userId
            // is opaque to this adapter and must survive containing one.
            String rest = status.substring("OK:".length());
            int separator = rest.indexOf(':');
            long expiresAtMillis = Long.parseLong(rest.substring(0, separator));
            String accountId = rest.substring(separator + 1);
            return new IssuedRefreshToken(decoded.sessionId().toString(), decoded.sessionId().toString(), accountId,
                    encodeToken(decoded.sessionId(), newSecret), Instant.ofEpochMilli(expiresAtMillis));
        }
        throw switch (status) {
            case "REUSE_DETECTED" -> new RefreshTokenReuseException(
                    "Refresh token reuse detected — session revoked");
            case "REVOKED" -> new InvalidRefreshTokenException("Refresh session has been revoked");
            case "EXPIRED" -> new InvalidRefreshTokenException("Refresh session has expired");
            case "NOT_FOUND" -> new InvalidRefreshTokenException("Unknown refresh session");
            case null, default -> new InvalidRefreshTokenException("Refresh session rejected");
        };
    }

    @Override
    public void revokeFamily(String rawToken) {
        DecodedToken decoded;
        try {
            decoded = decode(rawToken);
        } catch (InvalidRefreshTokenException ex) {
            return; // malformed token: nothing to revoke, treat as already logged out
        }

        try {
            redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    REVOKE_SCRIPT,
                    RScript.ReturnType.VALUE,
                    List.of(sessionKey(decoded.sessionId())),
                    String.valueOf(Instant.now().toEpochMilli()), decoded.tokenHash());
        } catch (RedisException ex) {
            throw new RefreshSessionStoreUnavailableException("Failed to revoke refresh session", ex);
        }
    }

    @Override
    public void revokeAllForAccount(AccountId accountId) {
        String revoked;
        try {
            revoked = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    REVOKE_ALL_SCRIPT,
                    RScript.ReturnType.VALUE,
                    List.of(accountIndexKey(accountId)),
                    String.valueOf(Instant.now().toEpochMilli()), KEY_PREFIX);
        } catch (RedisException ex) {
            throw new RefreshSessionStoreUnavailableException(
                    "Failed to revoke refresh sessions for account", ex);
        }

        log.info("Revoked all refresh sessions for account: id={}, sessions={}", accountId, revoked);
    }

    // ---- token encoding ----

    private byte[] randomSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        return secret;
    }

    private String encodeToken(UUID sessionId, byte[] secret) {
        ByteBuffer buffer = ByteBuffer.allocate(SESSION_ID_BYTES + SECRET_BYTES);
        buffer.putLong(sessionId.getMostSignificantBits());
        buffer.putLong(sessionId.getLeastSignificantBits());
        buffer.put(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    private DecodedToken decode(String rawToken) {
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(rawToken);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRefreshTokenException("Malformed refresh token");
        }
        if (raw.length != SESSION_ID_BYTES + SECRET_BYTES) {
            throw new InvalidRefreshTokenException("Malformed refresh token");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        UUID sessionId = new UUID(buffer.getLong(), buffer.getLong());
        byte[] secret = new byte[SECRET_BYTES];
        buffer.get(secret);
        return new DecodedToken(sessionId, sha256Hex(secret));
    }

    private String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String sessionKey(UUID sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private String accountIndexKey(AccountId accountId) {
        return ACCOUNT_INDEX_PREFIX + accountId;
    }

    private record DecodedToken(UUID sessionId, String tokenHash) {
    }
}
