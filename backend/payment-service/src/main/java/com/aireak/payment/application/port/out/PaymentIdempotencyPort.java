package com.aireak.payment.application.port.out;

/**
 * Outbound port: idempotency guard for payment initiation.
 * Implemented by RedissonPaymentIdempotencyAdapter in adapter/out/idempotency.
 */
public interface PaymentIdempotencyPort {

    /**
     * Atomically acquires the idempotency guard for {@code key}, or reports the state of whoever
     * already holds it — checking this instance's local cache first, then falling through to the
     * distributed guard. The TTL for both is owned entirely by the adapter.
     */
    PaymentIdempotencyResult acquire(String key);

    /**
     * Records the resolved paymentId for {@code key} in the local cache, so a subsequent retry
     * landing on this same instance can be answered by {@link #acquire} without a Redis or
     * database round trip. Callers must only call this once paymentId is definitively known (the
     * Payment row is durably persisted) — never for an in-flight/unresolved attempt.
     */
    void remember(String key, String paymentId);
}
