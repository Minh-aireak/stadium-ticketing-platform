package com.aireak.booking.application.port.out;

// Redis-backed fast-path dedup for POST /api/v1/bookings' Idempotency-Key — a performance
// layer, not the source of truth: bookings.idempotency_key's unique index stays authoritative
// (BookingRepository#findByIdempotencyKey), since a Redis eviction/restart must never let a
// duplicate through. Buys (1) fast-rejecting a concurrent in-flight duplicate and
// (2) answering a delayed replay without hitting Postgres.
public interface IdempotencyStore {

    // Atomically claims idempotencyKey for a fresh attempt, or reports the in-flight/completed
    // state of whoever already holds it.
    IdempotencyClaim claim(String idempotencyKey);

    // Records the final outcome so replays can be answered without re-running the saga.
    void complete(String idempotencyKey, String bookingId);

    // Releases a claim after a failed attempt so the key is available for a legit retry.
    void release(String idempotencyKey);
}
