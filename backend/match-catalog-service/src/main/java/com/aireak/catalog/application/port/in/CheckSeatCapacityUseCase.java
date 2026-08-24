package com.aireak.catalog.application.port.in;

import com.aireak.catalog.domain.model.SeatCapacityCheck;

/**
 * Inbound port: "would this many seats fit in what is currently left?", answered from the live
 * Redis counter without touching it.
 *
 * <p>A snapshot, not a reservation — see {@code SeatAvailabilityCounterPort#check}. Its value is
 * in rejecting-early and in leaving a record: every over-capacity answer is logged with the
 * requested count, the available count and the shortfall, which is what makes an oversell
 * traceable after the fact.
 */
public interface CheckSeatCapacityUseCase {

    SeatCapacityCheck checkCapacity(String showtimeId, int requestedSeats);
}
