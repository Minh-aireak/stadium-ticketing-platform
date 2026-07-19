package com.aireak.booking.domain.model;

/** Lifecycle states of a Booking aggregate. */
public enum BookingStatus {
    DRAFT,             // created but seats not yet reserved
    PENDING_PAYMENT,   // seats reserved, waiting for payment
    CONFIRMED,         // payment succeeded
    CANCELLED          // cancelled (payment failed, timeout, or user request)
}
