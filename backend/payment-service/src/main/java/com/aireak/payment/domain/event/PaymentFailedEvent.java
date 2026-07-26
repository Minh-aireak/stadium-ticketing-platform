package com.aireak.payment.domain.event;

import java.time.Instant;

public record PaymentFailedEvent(
        String paymentId, String bookingId, String reason, Instant occurredAt
) {
    public PaymentFailedEvent(String paymentId, String bookingId, String reason) {
        this(paymentId, bookingId, reason, Instant.now());
    }
}
