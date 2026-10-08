package com.aireak.catalog.application.port.out;

/**
 * Outbound port: the operations that change the live seat counter as sales are projected.
 *
 * <p><b>Why the counter needs a port of its own at all.</b> It used to be maintained as "decrement
 * Postgres, read the new total back, {@code SET} it into Redis". That is a read-modify-write
 * spanning two systems, and Redis offers no atomicity across it: two projections overlapping in
 * time both read before either writes, and the slower {@code SET} reinstates a count that was
 * already superseded — Redis then claims seats Postgres has sold, and nothing corrects it until the
 * key expires. Today that overlap is prevented by the sold-seat topic being keyed on
 * {@code showtimeId}, which puts one showtime's events on one partition and one consumer. That is a
 * property of the deployment, not of the code, and it is the wrong thing for the number customers
 * are shown to rest on.
 *
 * <p>{@link #decrement} closes the window by doing the whole read-check-write inside a single Lua
 * script. Redis runs a script to completion before serving any other command, so concurrent
 * decrements are serialized by Redis itself and every one of them observes the previous one's
 * result. No lock is involved, and no caller can lose an update.
 *
 * <p><b>Ordering.</b> The Redis leg runs first and Postgres second (see
 * {@code SoldSeatsProjectionService}), so the number customers are shown moves the instant a sale
 * is projected rather than after a database round-trip. Postgres stays the durable source of truth;
 * {@link #restore} exists so a failed database write can hand back exactly what the script
 * deducted, and {@link #reseed} so any branch that leaves the two out of step can re-derive the
 * counter from the committed database value.
 *
 * <p>This interface and {@link SeatCounterSeedPort} are the only ways to write the counter, and one
 * adapter implements both — the counter having exactly one writer is what makes the atomicity
 * above mean anything. {@link SeatCapacityQueryPort} and {@link LiveSeatAvailabilityPort} only read.
 */
public interface SeatCounterUpdatePort {

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

    /**
     * Atomically adds back seats a cancelled booking returned to sale — the projection of a return,
     * where {@link #restore} is the compensation of a failed decrement. Same script, same refusal to
     * recreate an absent key: the caller has just committed the return to Postgres, so re-deriving
     * the counter from there already counts it, and inventing a value here would not.
     */
    IncrementResult increment(String showtimeId, int seatCount);

    /**
     * Overwrites the counter with an authoritative value read from Postgres. Reserved for the
     * recovery branches — an expired key, a detected drift, a failed decrement — never for the
     * normal sale path, where an unconditional overwrite would reintroduce exactly the lost-update
     * race {@link #decrement} exists to remove.
     */
    void reseed(String showtimeId, int availableSeats);

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

    /**
     * The statuses an increment can end in. There is no CLAMPED: Redis does not know the showtime's
     * total, so it cannot cap — Postgres does ({@code LEAST(available + n, total_seats)}), and a
     * counter that ran ahead of it is corrected by the next reseed.
     */
    enum IncrementStatus {
        /** The seats were added. */
        APPLIED,
        /** No counter key exists — the caller must reseed from Postgres. */
        NOT_INITIALIZED,
        /** The key held something that is not a number — treated the same as a missing key. */
        CORRUPT,
        /** Redis could not be reached; nothing was written. */
        UNAVAILABLE
    }

    /** @param availableSeats what the counter holds after the script ran, or {@code 0} when nothing was written */
    record IncrementResult(IncrementStatus status, int availableSeats) {

        public static IncrementResult nothingWritten(IncrementStatus status) {
            return new IncrementResult(status, 0);
        }

        public boolean isClean() {
            return status == IncrementStatus.APPLIED;
        }
    }
}
