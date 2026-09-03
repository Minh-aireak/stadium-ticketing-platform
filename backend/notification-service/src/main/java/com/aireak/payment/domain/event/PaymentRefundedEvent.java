package com.aireak.payment.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code customerEmail} may be null, for the same reason {@link PaymentSucceededEvent}'s may: a
 * payment initiated with an internal-service token has no end-user identity behind it. Consumers
 * must treat it as optional — notification-service skips the refund email rather than failing.
 *
 * <p>This is notification-service's copy of payment-service's class, mirrored field-for-field —
 * {@code EventEnvelope} resolves the payload by fully-qualified class name, so the two must stay
 * in sync, package included. Same arrangement as {@link PaymentSucceededEvent}.
 */
public record PaymentRefundedEvent(
        String paymentId, String bookingId, String customerEmail,
        BigDecimal amount, String currency,
        String gatewayRefundId, String reason, Instant occurredAt
) {
    public PaymentRefundedEvent(String paymentId, String bookingId, String customerEmail,
                                 BigDecimal amount, String currency,
                                 String gatewayRefundId, String reason) {
        this(paymentId, bookingId, customerEmail, amount, currency, gatewayRefundId, reason, Instant.now());
    }
}
