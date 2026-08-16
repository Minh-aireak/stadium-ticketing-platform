package com.aireak.payment.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code customerEmail} may be null — a payment initiated with an internal-service token has no
 * end-user identity behind it. Consumers must treat it as optional (notification-service skips
 * the receipt email rather than failing).
 *
 * <p>Mirrored field-for-field in booking-service and notification-service (see their own copies of
 * this class) — {@code EventEnvelope} resolves the payload by fully-qualified class name, so the
 * three must stay in sync.
 */
public record PaymentSucceededEvent(
        String paymentId, String bookingId, String customerEmail,
        BigDecimal amount, String currency,
        String gatewayTransactionId, Instant occurredAt
) {
    public PaymentSucceededEvent(String paymentId, String bookingId, String customerEmail,
                                  BigDecimal amount, String currency, String gatewayTransactionId) {
        this(paymentId, bookingId, customerEmail, amount, currency, gatewayTransactionId, Instant.now());
    }
}
