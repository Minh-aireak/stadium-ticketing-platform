package com.aireak.catalog.domain.model;

/**
 * Result of asking "does this many seats still fit inside what the live counter says is left?".
 *
 * <p>The comparison itself lives here rather than in the adapter that reads Redis, so the rule
 * that decides "over capacity" has exactly one home: the adapter's job ends at producing a
 * number, and every caller — the sold-seat projection, any future pre-flight check — reaches the
 * same verdict from it.
 *
 * <p>{@link Verdict#UNKNOWN} is a distinct answer from {@link Verdict#WITHIN_CAPACITY}: it means
 * the live counter could not be read at all (never seeded, expired, or Redis unreachable), so
 * nothing was compared. Callers must not read it as "there is room".
 */
public record SeatCapacityCheck(String showtimeId, int requestedSeats, int availableSeats, Verdict verdict) {

    /** {@link #availableSeats} when the counter could not be read — never a real seat count. */
    public static final int UNKNOWN_AVAILABILITY = -1;

    public enum Verdict {
        /** The request fits: {@code requestedSeats <= availableSeats}. */
        WITHIN_CAPACITY,
        /** The request is larger than what the counter says is left. */
        EXCEEDS_CAPACITY,
        /** No counter value was available, so no comparison was made. */
        UNKNOWN
    }

    public static SeatCapacityCheck of(String showtimeId, int requestedSeats, int availableSeats) {
        Verdict verdict = requestedSeats > availableSeats ? Verdict.EXCEEDS_CAPACITY : Verdict.WITHIN_CAPACITY;
        return new SeatCapacityCheck(showtimeId, requestedSeats, availableSeats, verdict);
    }

    public static SeatCapacityCheck unknown(String showtimeId, int requestedSeats) {
        return new SeatCapacityCheck(showtimeId, requestedSeats, UNKNOWN_AVAILABILITY, Verdict.UNKNOWN);
    }

    public boolean exceedsCapacity() {
        return verdict == Verdict.EXCEEDS_CAPACITY;
    }

    public boolean isKnown() {
        return verdict != Verdict.UNKNOWN;
    }

    /** How many seats the request is short by — {@code 0} unless the verdict is EXCEEDS_CAPACITY. */
    public int shortfall() {
        return isKnown() ? Math.max(requestedSeats - availableSeats, 0) : 0;
    }

    /** What the counter would hold if the request were applied — floored at 0, as the counter itself is. */
    public int remainingAfter() {
        return isKnown() ? Math.max(availableSeats - requestedSeats, 0) : UNKNOWN_AVAILABILITY;
    }
}
