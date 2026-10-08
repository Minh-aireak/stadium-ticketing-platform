package com.aireak.booking.application.port.out;

import java.util.Collection;
import java.util.Set;

/**
 * Outbound port: a fast copy of which seats of a booking are cancelled, so a repeated cancel request
 * is answered without taking the lock or opening a write transaction.
 *
 * <p>A performance layer, never the source of truth — the {@code bookings} row is. It is written
 * only after that row has committed, and it is brought back in line whenever the database shows a
 * cancellation it does not know about. Losing it, or reading nothing from it, costs one database
 * round trip and nothing else, so an implementation fails open both ways.
 */
public interface CancelledSeatsStore {

    /** @return the seats recorded as cancelled; empty on a miss or when the store cannot be reached */
    Set<String> find(String bookingId);

    /** Replaces what is recorded for the booking with {@code cancelledSeatCodes}. */
    void record(String bookingId, Collection<String> cancelledSeatCodes);
}
