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

    /**
     * Hands {@code key} back after an attempt that held it ended without a payment, so the next
     * attempt gets a real try instead of being told one is already in flight.
     *
     * <p>Only ever for a key this caller was granted {@link PaymentIdempotencyResult.Acquired}
     * for. A key reported {@link PaymentIdempotencyResult.AlreadyHeld} belongs to a different
     * attempt that may be mid-charge, and releasing that one would let a second charge start
     * behind the first.
     */
    void release(String key);
}
