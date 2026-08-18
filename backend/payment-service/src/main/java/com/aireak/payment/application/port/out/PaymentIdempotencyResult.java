package com.aireak.payment.application.port.out;

// Outcome of {@link PaymentIdempotencyPort#acquire(String)}.
public sealed interface PaymentIdempotencyResult {

    // No one else holds this key (or the guard could not be checked — fail-open) — caller may
    // proceed with initiating the payment.
    record Acquired() implements PaymentIdempotencyResult {}

    // Redis confirms another attempt already holds this key, but this instance has no locally
    // cached paymentId for it — caller must resolve the existing payment (e.g. via the database).
    record AlreadyHeld() implements PaymentIdempotencyResult {}

    // This instance already resolved a paymentId for this key on a previous attempt.
    record Cached(String paymentId) implements PaymentIdempotencyResult {}
}
