package com.aireak.gateway.ratelimit;

/**
 * Baseline rate-limit policy table for the gateway. Numbers are the agreed baseline —
 * {@code requestedTokens}/{@code replenishRate} is what {@link RateLimitingWebFilter} uses to
 * compute {@code Retry-After} when a request is denied (time to refill enough tokens for one
 * more request of this policy's cost).
 *
 * <p>{@code HEALTH} is deliberately not a limiter: it is checked first by
 * {@link RateLimitingWebFilter} and bypasses rate limiting (and the Redis call) entirely.
 */
public enum RateLimitPolicy {

    // Coarse per-IP flood guard applied to EVERY inbound request, before JWT validation even
    // runs (see PreAuthRateLimitingWebFilter) — the only line of defense against an attacker
    // hammering a protected route with garbage/expired bearer tokens, since such requests are
    // rejected with 401 before they ever reach the per-route/per-user policies below. Sized well
    // above any single route's allowance (a legitimate session fans out across several routes
    // from one IP) so it only trips under actual flooding. Because it is that coarse, it is the
    // one policy enforced in-process rather than in Redis (see LocalIpTokenBucketLimiter): these
    // numbers are the cluster-wide intent, divided across gateway instances at runtime.
    PRE_AUTH_IP(KeyStrategy.IP, 20, 600, 3),
    LOGIN(KeyStrategy.IP, 1, 120, 12),
    REGISTER(KeyStrategy.IP, 1, 3600, 1200),
    // Same allowance as REGISTER, for the same reason: every accepted request makes
    // notification-service send an email, to an address the caller alone chose. What is being
    // protected is the mail provider's quota and the sender domain's reputation, not CPU — so the
    // limit has to be far tighter than the anonymous default this path would otherwise fall back to.
    PASSWORD_RESET_REQUEST(KeyStrategy.IP, 1, 3600, 1200),
    // Submitting the new password is credential-shaped, not email-shaped: sized like LOGIN so a
    // customer who trips the password policy a few times isn't locked out of their own reset link.
    PASSWORD_RESET_CONFIRM(KeyStrategy.IP, 1, 120, 12),
    // Resending a verification link is email-shaped, so it gets PASSWORD_RESET_REQUEST's numbers
    // and its reasoning: one accepted request, one email, to an address the caller alone chose.
    // A separate constant rather than sharing that one because the two buckets should not drain
    // each other -- a customer waiting on a verification mail has no way to ask for a password
    // reset, and vice versa.
    VERIFICATION_RESEND(KeyStrategy.IP, 1, 3600, 1200),
    // Following the link in the verification email. Link-shaped, not email-shaped: no accepted
    // request sends mail, so VERIFICATION_RESEND's numbers would punish the customer this endpoint
    // exists to serve. Sized like PASSWORD_RESET_CONFIRM, the other "user arrives holding a token
    // out of an email" endpoint, so a couple of double-clicks or a mail client that prefetches the
    // link do not lock someone out of activating their own account. Guessing the token is not what
    // this bounds -- it is 32 SecureRandom bytes (see RedisEmailVerificationTokenAdapter) -- it
    // bounds cheap anonymous noise on a path that is public by necessity.
    EMAIL_VERIFICATION_CONFIRM(KeyStrategy.IP, 1, 120, 12),
    REFRESH_TOKEN(KeyStrategy.USER_OR_IP, 1, 60, 3),
    // Stripe's webhook deliveries. Keyed by IP because there is no platform user behind them, and
    // sized for a provider that legitimately bursts (a backlog being flushed, retries of earlier
    // failures) — throttling Stripe does not protect anything here, it just delays the payment
    // reconciliation this endpoint exists to perform, and Stripe gives up after its retry window.
    // Still bounded rather than exempt: the path is publicly reachable, so anyone can post to it,
    // and a bad signature is cheap to reject but not free.
    STRIPE_WEBHOOK(KeyStrategy.IP, 20, 600, 1),
    READ_ANONYMOUS(KeyStrategy.IP, 5, 300, 3),
    READ_AUTHENTICATED(KeyStrategy.USER, 10, 600, 3),
    BOOKING(KeyStrategy.USER, 1, 90, 3),
    PAYMENT(KeyStrategy.USER, 1, 60, 6),
    NOTIFICATION(KeyStrategy.USER, 1, 60, 1),
    DEFAULT_AUTHENTICATED(KeyStrategy.USER, 5, 300, 3),
    DEFAULT_ANONYMOUS(KeyStrategy.IP, 1, 60, 2);

    private final KeyStrategy keyStrategy;
    private final int replenishRate;
    private final long burstCapacity;
    private final int requestedTokens;

    RateLimitPolicy(KeyStrategy keyStrategy, int replenishRate, long burstCapacity, int requestedTokens) {
        this.keyStrategy = keyStrategy;
        this.replenishRate = replenishRate;
        this.burstCapacity = burstCapacity;
        this.requestedTokens = requestedTokens;
    }

    public KeyStrategy keyStrategy() {
        return keyStrategy;
    }

    public int replenishRate() {
        return replenishRate;
    }

    public long burstCapacity() {
        return burstCapacity;
    }

    public int requestedTokens() {
        return requestedTokens;
    }

    /** Seconds to accumulate enough tokens for one more request, assuming the bucket is near empty. */
    public long retryAfterSeconds() {
        return (long) Math.ceil(requestedTokens / (double) replenishRate);
    }

    public enum KeyStrategy {
        IP,
        USER,
        /** user:{userId} if the caller's identity can be verified, ip:{clientIp} otherwise. */
        USER_OR_IP
    }
}
