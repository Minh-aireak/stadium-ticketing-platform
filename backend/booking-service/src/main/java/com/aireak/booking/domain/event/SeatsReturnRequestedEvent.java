package com.aireak.booking.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Raised when a customer cancels seats, asking ticket-inventory-service to give them back: drop the
 * booking's holds on them and, for seats already SOLD to this booking, put them back on sale.
 *
 * <p>A request aimed at another service, like {@link RefundRequestedEvent}, and on the outbox for
 * the same reason: it is written in the transaction that cancels the seats, so the seats cannot stay
 * locked out because inventory happened to be down at that moment. Mirrored field-for-field in
 * ticket-inventory-service.
 */
public record SeatsReturnRequestedEvent(String bookingId, String showtimeId, List<String> seatCodes,
                                        String reason, Instant occurredAt) {

    public SeatsReturnRequestedEvent {
        seatCodes = List.copyOf(seatCodes);
    }

    public SeatsReturnRequestedEvent(String bookingId, String showtimeId, List<String> seatCodes, String reason) {
        this(bookingId, showtimeId, seatCodes, reason, Instant.now());
    }
}
