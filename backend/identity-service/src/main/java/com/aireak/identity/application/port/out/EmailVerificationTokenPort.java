package com.aireak.identity.application.port.out;

import com.aireak.identity.domain.model.AccountId;

/**
 * Outbound port: one-time email verification tokens, backed by Redis with a TTL.
 *
 * <p>{@link #generate()} is pure (no I/O) so it can run before the Account is persisted;
 * {@link #store} binds the token to the now-persisted account.
 *
 * <p><strong>Reading and spending are two calls on purpose.</strong> They used to be one atomic
 * get-and-delete, called first inside the activating transaction — so a Redis delete that cannot
 * be rolled back happened before a database write that can. Any failure after it (the save, the
 * outbox insert, the connection dropping on commit) rolled the account back to
 * PENDING_VERIFICATION and left the token gone. Nothing recovers from that: the account cannot
 * log in, cannot be registered again, and the link in the customer's inbox is now dead. So
 * {@link #peek} resolves the token without spending it and {@link #invalidate} spends it once the
 * activation has committed.
 *
 * <p>What that gives up is the atomicity that stopped two concurrent clicks both resolving the
 * same token. It costs nothing: both then reach an activation that is idempotent by status, so
 * the second is a no-op rather than a second activation, and the token still activates exactly
 * one account — the one it was minted for.
 */
public interface EmailVerificationTokenPort {

    String generate();

    void store(String rawToken, AccountId accountId);

    /**
     * Resolves a token to its account <em>without</em> spending it, so a failed activation leaves
     * the customer's link usable.
     *
     * @throws com.aireak.identity.domain.exception.InvalidVerificationTokenException token is
     *         missing, unknown, or expired
     */
    AccountId peek(String rawToken);

    /**
     * Spends the token. Called only after the activation it authorised has committed. Idempotent
     * and never throws for a token that is already gone — a redundant delete is not a failure the
     * caller can do anything with, and by this point the account is already active.
     */
    void invalidate(String rawToken);
}
