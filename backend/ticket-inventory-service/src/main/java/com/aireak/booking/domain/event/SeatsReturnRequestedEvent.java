package com.aireak.booking.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Consumer-side copy of booking-service's event of the same name — same package and shape, since
 * {@code EventEnvelope} resolves the payload by class name. A customer cancelled these seats of
 * {@code bookingId}: drop the booking's holds on them, and put any already SOLD to it back on sale.
 */
public record SeatsReturnRequestedEvent(String bookingId, String showtimeId, List<String> seatCodes,
                                        String reason, Instant occurredAt) {
}
