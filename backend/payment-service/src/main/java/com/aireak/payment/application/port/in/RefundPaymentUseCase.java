package com.aireak.payment.application.port.in;

import java.util.Optional;

/**
 * Inbound port: refund a SUCCEEDED payment for a booking. Called internally by booking-service
 * when a match is cancelled — never directly by a customer (see PaymentController).
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
