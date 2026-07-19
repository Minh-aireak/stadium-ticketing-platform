package com.aireak.payment.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentSucceededEvent(
        String paymentId, String bookingId,
        BigDecimal amount, String currency,
        String gatewayTransactionId, Instant occurredAt
) {
    public PaymentSucceededEvent(String paymentId, String bookingId,
                                  BigDecimal amount, String currency, String gatewayTransactionId) {
        this(paymentId, bookingId, amount, currency, gatewayTransactionId, Instant.now());
    }
}
