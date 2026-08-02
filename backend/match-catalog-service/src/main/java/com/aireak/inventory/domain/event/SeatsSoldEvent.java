package com.aireak.inventory.domain.event;

import java.time.Instant;
import java.util.List;

/** Wire-compatible mirror of ticket-inventory-service's SeatsSoldEvent. */
public record SeatsSoldEvent(String showtimeId, List<?> seatCodes, Instant occurredAt) {
}
