package com.aireak.identity.application.port.out;

import com.aireak.identity.application.port.out.dto.IssuedRefreshToken;
import com.aireak.identity.domain.model.AccountId;

/**
 * Outbound port: Redis-backed refresh session storage.
 *
 * <p>Only session state is stored (userId, tokenHash, familyId, timestamps) — never the raw
 * opaque token. Rotation and reuse detection must be atomic with respect to concurrent callers;
 * implementations are expected to use a single atomic Redis operation (e.g. a Lua script),
 * never a non-atomic read-then-write sequence.
 */
public interface RefreshSessionStorePort {

    /**
     * Starts a brand-new token family (login). Always succeeds.
     */
    IssuedRefreshToken createSession(AccountId accountId);

    /**
     * Atomically validates {@code rawToken} and rotates it to a new opaque token within the
     * same family.
     *
     * @throws com.aireak.identity.domain.exception.InvalidRefreshTokenException token is malformed,
     *         unknown, expired, or its family was already revoked
     * @throws com.aireak.identity.domain.exception.RefreshTokenReuseException token had already
     *         been rotated once before (reuse of a stale token) — the whole family is revoked
     *         as a side effect before this is thrown
     */
    IssuedRefreshToken rotate(String rawToken);

    /**
     * Revokes the entire token family that {@code rawToken} belongs to. Idempotent —
     * an unknown/malformed token is treated as "already logged out", not an error.
     */
    void revokeFamily(String rawToken);

    /**
     * Revokes every refresh session the account currently has, on every device — the caller
     * holds no token here, only the account id. Used after a password reset: whoever forced the
     * reset (or whoever the customer was resetting <em>because of</em>) must not keep a live
     * session that survives the new password.
     *
     * <p>Idempotent, and safe for an account with no sessions at all.
     */
    void revokeAllForAccount(AccountId accountId);
}
