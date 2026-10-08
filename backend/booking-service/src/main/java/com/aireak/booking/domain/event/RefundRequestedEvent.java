package com.aireak.booking.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Raised when money a booking took has to go back to the customer: a cancelled match, a payment
 * that landed after its booking was already gone, or seats the customer cancelled themselves.
 *
 * <p>{@code refundRequestId} is what makes the request safe to deliver twice. A booking can now be
 * refunded more than once — one seat today, another tomorrow — so payment-service can no longer
 * treat "already refunded" as "this request was already honoured"; it records each id it has
 * applied and skips one it has seen. The same id is the gateway idempotency key, so a redelivery
 * cannot move money twice even before that record is written.
 *
 * <p>{@code amount} is null when the whole remaining balance is owed (the first two cases above):
 * payment-service resolves the payment from {@code bookingId} and refunds what is left of what it
 * actually captured, which is the only figure that can be right if the two services disagree. For
 * cancelled seats it is the sum of those seats' prices, and {@code seatCodes} names them for the
 * record. Mirrored field-for-field in payment-service.
 */
public record RefundRequestedEvent(String bookingId, String refundRequestId, BigDecimal amount, String currency,
                                   List<String> seatCodes, String reason, Instant occurredAt) {

    public RefundRequestedEvent {
        seatCodes = seatCodes == null ? List.of() : List.copyOf(seatCodes);
    }

    /** Everything still refundable on the booking's payment. */
    public static RefundRequestedEvent ofRemainingBalance(String bookingId, String reason) {
        return new RefundRequestedEvent(bookingId, UUID.randomUUID().toString(), null, null, List.of(),
                reason, Instant.now());
    }

    /** The price of {@code seatCodes}, which the customer has just cancelled. */
    public static RefundRequestedEvent forSeats(String bookingId, BigDecimal amount, String currency,
                                                List<String> seatCodes, String reason) {
        return new RefundRequestedEvent(bookingId, UUID.randomUUID().toString(), amount, currency, seatCodes,
                reason, Instant.now());
    }
}
