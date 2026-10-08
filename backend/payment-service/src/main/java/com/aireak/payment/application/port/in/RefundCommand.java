package com.aireak.payment.application.port.in;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * @param bookingId       whose payment to refund
 * @param refundRequestId booking-service's id for this request, what makes it safe to deliver twice;
 *                        null only on a request raised before it carried one, which is then keyed by
 *                        the payment itself and can only ever be a full refund
 * @param amount          how much to refund; null for everything still refundable
 * @param reason          why, for the record and the customer's email
 */
public record RefundCommand(String bookingId, String refundRequestId, BigDecimal amount, String reason) {

    public RefundCommand {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        if (amount != null && amount.signum() <= 0) {
            throw new IllegalArgumentException("A refund amount must be positive: " + amount);
        }
    }

    /** Everything still refundable on the booking's payment. */
    public static RefundCommand remainingBalance(String bookingId, String reason) {
        return new RefundCommand(bookingId, null, null, reason);
    }
}
