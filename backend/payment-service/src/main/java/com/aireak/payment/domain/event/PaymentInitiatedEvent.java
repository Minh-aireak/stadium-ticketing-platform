package com.aireak.payment.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentInitiatedEvent(
        String paymentId, String bookingId,
        BigDecimal amount, String currency, Instant occurredAt
) {
    public PaymentInitiatedEvent(String paymentId, String bookingId, BigDecimal amount, String currency) {
        this(paymentId, bookingId, amount, currency, Instant.now());
    }
}
