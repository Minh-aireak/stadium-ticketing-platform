package com.aireak.identity.application.port.out;

import com.aireak.identity.domain.model.AccountId;

/**
 * Outbound port: one-time email verification tokens, backed by Redis with a TTL.
 *
 * <p>{@link #generate()} is pure (no I/O) so it can run before the Account is persisted;
 * {@link #store} binds the token to the now-persisted account. {@link #consume} is an atomic
 * get-and-delete so a token can activate an account at most once.
 */
public interface EmailVerificationTokenPort {

    String generate();

    void store(String rawToken, AccountId accountId);

    /**
     * @throws com.aireak.identity.domain.exception.InvalidVerificationTokenException token is
     *         missing, unknown, or expired
     */
    AccountId consume(String rawToken);
}
