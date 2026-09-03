package com.aireak.inventory.domain.exception;

import com.aireak.common.exception.ResourceNotFoundException;

/**
 * Thrown when a SeatInventory for the given showtime is not found → 404, matching what
 * {@code SeatInventoryController#getSeatMap} has always answered for the same missing showtime.
 */
public class SeatInventoryNotFoundException extends ResourceNotFoundException {
    public SeatInventoryNotFoundException(String showtimeId) {
        super("SeatInventory not found for showtime: " + showtimeId);
    }
}
