package com.aireak.inventory.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Wire-compatible mirror of ticket-inventory-service's SeatsReturnedEvent: seats a cancelled paid
 * booking put back on sale. Arrives on the same topic as {@link SeatsSoldEvent}.
 */
public record SeatsReturnedEvent(String showtimeId, String bookingId, List<?> seatCodes, Instant occurredAt) {
}
