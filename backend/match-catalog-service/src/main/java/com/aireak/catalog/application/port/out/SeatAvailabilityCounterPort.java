package com.aireak.catalog.application.port.out;

import com.aireak.catalog.domain.model.SeatCapacityCheck;

/**
 * Outbound port: the single writer of the live available-seat counter that
 * {@link LiveSeatAvailabilityPort} reads.
 *
 * <p><b>Why this port exists at all.</b> The counter used to be maintained as "decrement Postgres,
 * read the new total back, {@code SET} it into Redis". That is a read-modify-write spanning two
 * systems, and Redis offers no atomicity across it: two projections overlapping in time both read
 * before either writes, and the slower {@code SET} reinstates a count that was already superseded —
 * Redis then claims seats Postgres has sold, and nothing corrects it until the key expires. Today
 * that overlap is prevented by the sold-seat topic being keyed on {@code showtimeId}, which puts one
 * showtime's events on one partition and one consumer. That is a property of the deployment, not of
 * the code, and it is the wrong thing for the number customers are shown to rest on.
 *
 * <p>{@link #decrement} closes that window by doing the whole read-check-write inside a single
 * Lua script. Redis runs a script to completion before serving any other command, so concurrent
 * decrements are serialized by Redis itself and every one of them observes the previous one's
 * result. No lock is involved, and no caller can lose an update.
 *
 * <p><b>Ordering.</b> The Redis leg runs first and Postgres second (see
 * {@code SoldSeatsProjectionService}), so the number customers are shown moves the instant a sale
 * is projected rather than after a database round-trip. Postgres stays the durable source of
 * truth; {@link #restore} exists so a failed database write can hand back exactly what the script
 * deducted, and {@link #reseed} so any branch that leaves the two out of step can re-derive the
 * counter from the committed database value.
 */
public interface SeatAvailabilityCounterPort {

    /**
     * Seeds a brand-new showtime's counter with its full capacity — called once, when the showtime
     * is created, so the counter exists before the first customer ever looks at it.
     *
     * <p>Seeds only when the key is absent. A retried or replayed initialization must never write
     * full capacity over a counter that has already sold seats, so an existing value is reported
     * back as {@link SeedResult#ALREADY_PRESENT} and left untouched.
     */
    SeedResult initialize(String showtimeId, int totalSeats);

    /**
     * Overwrites the counter with an authoritative value read from Postgres. Reserved for the
     * recovery branches — an expired key, a detected drift, a failed decrement — never for the
     * normal sale path, where an unconditional overwrite would reintroduce exactly the lost-update
     * race {@link #decrement} exists to remove.
     */
    void reseed(String showtimeId, int availableSeats);

    /**
     * Reads the counter and reports whether {@code requestedSeats} still fits inside it. Purely a
     * read: it changes nothing and holds nothing, so its answer is a snapshot that a concurrent
     * sale can invalidate a microsecond later. {@link #decrement} remains the only authoritative
     * gate; this is for rejecting-early and for making an over-capacity request visible in the logs.
     */
    SeatCapacityCheck check(String showtimeId, int requestedSeats);

    /**
     * Atomically subtracts {@code seatCount} from the counter, floored at zero, and reports what
     * actually happened. The floor matters: this projects a sale that has already completed in
     * ticket-inventory-service, so the counter must absorb it rather than refuse it — but a floor
     * that had to engage means Redis and Postgres disagreed, which the caller reconciles.
     */
    DecrementResult decrement(String showtimeId, int seatCount);

    /**
     * Adds {@code seatCount} back, undoing a {@link #decrement} whose database leg did not stick.
     * Pass {@link DecrementResult#deductedSeats()}, not the originally requested count — a clamped
     * decrement took less than it was asked for.
     */
    void restore(String showtimeId, int seatCount);

    enum SeedResult {
        /** The key was absent and now holds the showtime's full capacity. */
        SEEDED,
        /** A counter was already there and was deliberately left alone. */
        ALREADY_PRESENT,
        /** Redis could not be reached; nothing was written. */
        UNAVAILABLE
    }

    enum DecrementStatus {
        /** The full requested count came off the counter. */
        APPLIED,
        /** The counter held less than was requested and stopped at zero — Redis and Postgres disagree. */
        CLAMPED,
        /** No counter key exists (never seeded, or expired) — the caller must reseed from Postgres. */
        NOT_INITIALIZED,
        /** The key held something that is not a number — treated the same as a missing key. */
        CORRUPT,
        /** Redis could not be reached; nothing was written. */
        UNAVAILABLE
    }

    /**
     * @param remainingSeats  what the counter holds after the script ran, or {@code 0} when nothing was written
     * @param deductedSeats   what actually came off — equal to the request unless the floor engaged;
     *                        this is the amount {@link #restore} must hand back
     */
    record DecrementResult(DecrementStatus status, int remainingSeats, int deductedSeats) {

        public static DecrementResult nothingWritten(DecrementStatus status) {
            return new DecrementResult(status, 0, 0);
        }

        /** True when the script ran and changed the counter — the only case {@link #restore} can undo. */
        public boolean wrote() {
            return deductedSeats > 0;
        }

        /** True when Redis ended up agreeing with the request; anything else needs a reseed from Postgres. */
        public boolean isClean() {
            return status == DecrementStatus.APPLIED;
        }
    }
}
