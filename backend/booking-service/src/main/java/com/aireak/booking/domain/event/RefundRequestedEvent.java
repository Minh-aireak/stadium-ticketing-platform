package com.aireak.booking.domain.event;

import java.time.Instant;

/**
 * Raised when a booking that was already paid for stops being valid, so the money owes its way
 * back to the customer. Carries no amount: payment-service resolves the payment from
 * {@code bookingId} and refunds what was actually captured, which is the only figure that can be
 * right if the two services ever disagree.
 */
public record RefundRequestedEvent(String bookingId, String reason, Instant occurredAt) {

    public RefundRequestedEvent(String bookingId, String reason) {
        this(bookingId, reason, Instant.now());
    }
}
