package com.aireak.common.kafka;

/**
 * Central registry of Kafka topic names used across all services.
 * All producers and consumers must reference these constants — no magic strings.
 */
public final class KafkaTopics {

    private KafkaTopics() {
        // utility class — no instantiation
    }

    // --- Identity events ---
    public static final String ACCOUNT_REGISTERED = "identity.account.registered";
    public static final String ACCOUNT_ACTIVATED  = "identity.account.activated";
    public static final String PASSWORD_RESET_REQUESTED = "identity.account.password-reset-requested";

    // --- Catalog events ---
    public static final String MATCH_PUBLISHED    = "catalog.match.published";
    public static final String MATCH_CANCELLED    = "catalog.match.cancelled";
    public static final String MATCH_COMPLETED    = "catalog.match.completed";
    public static final String SHOWTIME_CREATED   = "catalog.showtime.created";

    // --- Inventory events ---
    public static final String SEATS_RESERVED     = "inventory.seats.reserved";
    public static final String SEATS_RELEASED     = "inventory.seats.released";
    public static final String SEATS_SOLD         = "inventory.seats.sold";
    // Seats a cancelled paid booking hands back ride the SAME topic as sales, keyed by showtimeId,
    // on purpose. match-catalog-service applies both to one counter, and the reseed it falls back
    // to when Redis and Postgres disagree is a read-then-write that is only safe while one consumer
    // applies a showtime's changes one at a time (see SoldSeatsProjectionService). A topic of their
    // own would be a second consumer running beside the first, free to reseed in the middle of a
    // sale it has not seen.
    public static final String SEATS_RETURNED     = SEATS_SOLD;

    // --- Booking events ---
    public static final String BOOKING_CREATED    = "booking.booking.created";
    public static final String BOOKING_CONFIRMED  = "booking.booking.confirmed";
    public static final String BOOKING_CANCELLED  = "booking.booking.cancelled";
    // Not a fourth Booking lifecycle event but a request aimed at payment-service: a cancelled
    // booking that was already paid for owes its customer a refund. It rides the outbox for the
    // same reason the others do -- the row is written in the transaction that cancels the booking,
    // so the refund cannot be lost if payment-service is down at that moment.
    public static final String REFUND_REQUESTED   = "booking.refund.requested";
    // A request aimed at ticket-inventory-service, like REFUND_REQUESTED is at payment-service: a
    // customer cancelled seats, so their holds must go and any seat already SOLD to the booking
    // must go back on sale. Written in the cancelling transaction, so it cannot be lost either.
    public static final String SEATS_RETURN_REQUESTED = "booking.seats.return-requested";

    // --- Payment events ---
    public static final String PAYMENT_INITIATED  = "payment.payment.initiated";
    public static final String PAYMENT_SUCCEEDED  = "payment.payment.succeeded";
    public static final String PAYMENT_FAILED     = "payment.payment.failed";
    public static final String PAYMENT_REFUNDED   = "payment.payment.refunded";
}
