package com.aireak.payment.application.port.in;

import java.util.Optional;

/**
 * Inbound port: refund a SUCCEEDED payment for a booking, in full or in part.
 *
 * <p>Driven by exactly one adapter — {@code RefundRequestedConsumer}, off booking-service's
 * {@code booking.refund.requested} topic, which is where a cancelled match, a payment that landed
 * after its booking was already gone, and seats a customer cancelled all end up. There is no HTTP
 * route onto this: a refund can only be requested by a service that can write to booking-service's
 * outbox, never by a client.
 */
public interface RefundPaymentUseCase {

    /**
     * No-ops (returns empty) when there is no payment for this booking, it was never SUCCEEDED
     * (still INITIATED, FAILED), it has nothing left to refund (REFUNDED), or this exact request was
     * already applied. Refunding is only ever meaningful for money that was actually taken, and the
     * caller may legitimately deliver a request twice, so a repeat must stay a safe no-op rather
     * than an error — or a second refund.
     *
     * @return the paymentId if a refund was issued, empty otherwise
     */
    Optional<String> refund(RefundCommand command);

    /** Everything still refundable. Same no-op rules as {@link #refund}. */
    default Optional<String> refundByBookingId(String bookingId, String reason) {
        return refund(RefundCommand.remainingBalance(bookingId, reason));
    }
}
