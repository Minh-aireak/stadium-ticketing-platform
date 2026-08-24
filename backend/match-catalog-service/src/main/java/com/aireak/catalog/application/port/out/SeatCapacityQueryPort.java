package com.aireak.catalog.application.port.out;

import com.aireak.catalog.domain.model.SeatCapacityCheck;

/**
 * Outbound port: reads the live seat counter without touching it.
 *
 * <p>Read-only by type, not just by convention — a client that only needs to ask a question cannot
 * accidentally reach a method that changes the answer. That is the whole reason this is its own
 * interface rather than a method on {@link SeatCounterUpdatePort}.
 */
public interface SeatCapacityQueryPort {

    /**
     * Reads the counter and reports whether {@code requestedSeats} still fits inside it.
     *
     * <p>A snapshot: a concurrent sale can invalidate the answer a microsecond later.
     * {@link SeatCounterUpdatePort#decrement} remains the only authoritative gate; this is for
     * rejecting-early and for making an over-capacity request visible in the logs and metrics.
     */
    SeatCapacityCheck check(String showtimeId, int requestedSeats);
}
