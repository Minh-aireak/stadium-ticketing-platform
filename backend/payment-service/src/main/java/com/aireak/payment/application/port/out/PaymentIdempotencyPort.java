package com.aireak.payment.application.port.out;

import java.time.Duration;

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
}
