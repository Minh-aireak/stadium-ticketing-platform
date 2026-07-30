package com.aireak.payment.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentRefundedEvent(
        String paymentId, String bookingId, BigDecimal amount, String currency,
        String gatewayRefundId, String reason, Instant occurredAt
) {
    public PaymentRefundedEvent(String paymentId, String bookingId, BigDecimal amount, String currency,
                                 String gatewayRefundId, String reason) {
        this(paymentId, bookingId, amount, currency, gatewayRefundId, reason, Instant.now());
    }
}
