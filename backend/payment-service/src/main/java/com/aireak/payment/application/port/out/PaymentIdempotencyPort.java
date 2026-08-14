package com.aireak.payment.application.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * Outbound port: idempotency guard for payment initiation.
 * Implemented by RedissonPaymentIdempotencyAdapter in adapter/out/idempotency.
 */
public interface PaymentIdempotencyPort {

    /**
     * Attempts to acquire the idempotency guard for the given key.
     *
     * @return true if this call acquired the key for the first time, or if the
     *         guard could not be checked (Redis unavailable — fail-open, callers
     *         must have a backstop for the resulting race window);
     *         false only if the key is already held by a prior, still-valid attempt.
     */
    boolean tryAcquire(String key, Duration ttl);

    /**
     * Returns the paymentId this same instance already resolved for {@code key} (via a prior
     * {@link #remember} call) — a pure in-memory lookup, never touches Redis or the database.
     * Empty means "unknown to this instance", NOT "no payment exists for this key"; callers must
     * still fall through to the normal {@link #tryAcquire}-guarded flow on a miss.
     */
    Optional<String> cachedPaymentId(String key);

    /**
     * Records the resolved paymentId for {@code key} in the local cache, so a subsequent retry
     * landing on this same instance can be answered by {@link #cachedPaymentId} without a Redis or
     * database round trip. Callers must only call this once paymentId is definitively known (the
     * Payment row is durably persisted) — never for an in-flight/unresolved attempt.
     */
    void remember(String key, String paymentId);
}
