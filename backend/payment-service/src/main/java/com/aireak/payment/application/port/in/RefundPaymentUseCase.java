package com.aireak.payment.application.port.in;

import java.util.Optional;

/**
 * Inbound port: refund a SUCCEEDED payment for a booking.
 *
 * <p>Driven by exactly one adapter — {@code RefundRequestedConsumer}, off booking-service's
 * {@code booking.refund.requested} topic, which is where a cancelled match and a payment that
 * landed after its booking was already gone both end up. There is no HTTP route onto this: the
 * endpoint that used to offer one outlived its only caller and was removed, so a refund can only
 * be requested by a service that can write to booking-service's outbox, never by a client.
 */
public interface RefundPaymentUseCase {
    /**
     * No-ops (returns empty) when there is no payment for this booking, or it was never
     * SUCCEEDED (e.g. still INITIATED, already FAILED, or already REFUNDED) — refunding is only
     * ever meaningful for money that was actually taken, and the caller may legitimately retry
     * this call, so a second refund attempt on an already-REFUNDED payment must stay a safe no-op
     * rather than an error.
     *
     * @return the paymentId if a refund was issued, empty otherwise
     */
    Optional<String> refundByBookingId(String bookingId, String reason);
}
