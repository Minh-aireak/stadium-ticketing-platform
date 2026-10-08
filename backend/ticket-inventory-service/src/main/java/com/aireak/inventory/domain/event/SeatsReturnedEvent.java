package com.aireak.inventory.domain.event;

import com.aireak.inventory.domain.model.SeatCode;

import java.time.Instant;
import java.util.List;

/**
 * Raised when seats a booking had bought go back on sale because its customer cancelled them —
 * the inverse of {@link SeatsSoldEvent}, and published on the same stream so match-catalog-service
 * applies sales and returns of one showtime in order (see {@code KafkaTopics#SEATS_RETURNED}).
 *
 * <p>Carries only seats that actually went from SOLD to AVAILABLE: catalog adds exactly this many
 * back to the showtime's available count, so a seat that was merely held, never sold, must not
 * appear here — it was never taken off that count in the first place.
 */
public record SeatsReturnedEvent(String showtimeId, String bookingId, List<SeatCode> seatCodes, Instant occurredAt) {

    public SeatsReturnedEvent(String showtimeId, String bookingId, List<SeatCode> seatCodes) {
        this(showtimeId, bookingId, seatCodes, Instant.now());
    }
}
