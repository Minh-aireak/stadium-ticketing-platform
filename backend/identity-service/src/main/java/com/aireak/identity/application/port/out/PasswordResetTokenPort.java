package com.aireak.identity.application.port.out;

import com.aireak.identity.domain.model.AccountId;

/**
 * Outbound port: one-time password-reset tokens, backed by Redis with a short TTL.
 *
 * <p>Same shape as {@link EmailVerificationTokenPort}, including the split between {@link #peek}
 * and {@link #invalidate} and the reason for it — a Redis delete that cannot be rolled back must
 * not precede a database write that can. The consequence here is milder than for verification:
 * a customer whose reset died mid-way can always ask for another link. It is still a link that
 * silently stopped working, on the endpoint whose javadoc promises not to burn one.
 */
public interface PasswordResetTokenPort {

    String generate();

    void store(String rawToken, AccountId accountId);

    /**
     * Resolves a token to its account <em>without</em> spending it.
     *
     * @throws com.aireak.identity.domain.exception.InvalidPasswordResetTokenException token is
     *         missing, unknown, or expired
     */
    AccountId peek(String rawToken);

    /** Spends the token, once the password change it authorised has committed. Idempotent. */
    void invalidate(String rawToken);
}
