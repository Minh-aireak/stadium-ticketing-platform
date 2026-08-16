package com.aireak.identity.application.port.out;

import com.aireak.identity.domain.model.AccountId;

/**
 * Outbound port: one-time password-reset tokens, backed by Redis with a short TTL.
 *
 * <p>Same shape as {@link EmailVerificationTokenPort} — {@link #generate()} is pure so it can run
 * before anything is persisted, {@link #store} binds the token to an account, and {@link #consume}
 * is an atomic get-and-delete so one token can reset a password at most once.
 */
public interface PasswordResetTokenPort {

    String generate();

    void store(String rawToken, AccountId accountId);

    /**
     * @throws com.aireak.identity.domain.exception.InvalidPasswordResetTokenException token is
     *         missing, unknown, or expired
     */
    AccountId consume(String rawToken);
}
