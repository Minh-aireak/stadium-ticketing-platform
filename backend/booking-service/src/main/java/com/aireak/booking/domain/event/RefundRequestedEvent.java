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

    /**
     * Everything still refundable on the booking's payment. A fresh id is right here because the
     * caller raises this exactly once, in the transaction that moves the booking to CANCELLED;
     * redeliveries of the outbox row carry the same id.
     */
    public static RefundRequestedEvent ofRemainingBalance(String bookingId, String reason) {
        return new RefundRequestedEvent(bookingId, UUID.randomUUID().toString(), null, null, List.of(),
                reason, Instant.now());
    }

    /**
     * Everything still refundable, for a payment that succeeded after its booking was cancelled.
     *
     * <p>Keyed by the booking, not random, because this one is NOT raised once: every delivery of
     * PAYMENT_SUCCEEDED for a cancelled booking raises it again, and Kafka delivers at least once. A
     * random id made each redelivery a new request, so payment-service could only skip it by
     * noticing the payment was already REFUNDED — and when the first refund had gone out at Stripe
     * but failed to persist, the next one went back to Stripe under a new idempotency key and was
     * refused there, dead-lettering. A booking has one payment, so it owes at most one late-payment
     * refund: the same id every time lets payment-service skip it by id, and lets Stripe answer a
     * retried refund with the original instead of refusing it.
     */
    public static RefundRequestedEvent forLatePayment(String bookingId, String reason) {
        return new RefundRequestedEvent(bookingId, "late-payment:" + bookingId, null, null, List.of(),
                reason, Instant.now());
    }

    /** The price of {@code seatCodes}, which the customer has just cancelled. */
    public static RefundRequestedEvent forSeats(String bookingId, BigDecimal amount, String currency,
                                                List<String> seatCodes, String reason) {
        return new RefundRequestedEvent(bookingId, UUID.randomUUID().toString(), amount, currency, seatCodes,
                reason, Instant.now());
    }
}
