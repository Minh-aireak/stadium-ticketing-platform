package com.aireak.inventory.domain.exception;

import com.aireak.common.exception.DomainException;

/** Thrown when a SeatInventory for the given showtime is not found. */
public class SeatInventoryNotFoundException extends DomainException {
    public SeatInventoryNotFoundException(String showtimeId) {
        super("SeatInventory not found for showtime: " + showtimeId);
    }
}
