package com.aireak.catalog.application.port.out;

/** Applies a sold-seat event to the catalog read model exactly once. */
public interface SeatAvailabilityProjectionPort {
    boolean decrementAvailableSeats(String eventId, String showtimeId, int soldSeatCount);
}
