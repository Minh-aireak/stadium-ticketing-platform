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

    // --- Booking events ---
    public static final String BOOKING_CREATED    = "booking.booking.created";
    public static final String BOOKING_CONFIRMED  = "booking.booking.confirmed";
    public static final String BOOKING_CANCELLED  = "booking.booking.cancelled";

    // --- Payment events ---
    public static final String PAYMENT_INITIATED  = "payment.payment.initiated";
    public static final String PAYMENT_SUCCEEDED  = "payment.payment.succeeded";
    public static final String PAYMENT_FAILED     = "payment.payment.failed";
    public static final String PAYMENT_REFUNDED   = "payment.payment.refunded";
}
