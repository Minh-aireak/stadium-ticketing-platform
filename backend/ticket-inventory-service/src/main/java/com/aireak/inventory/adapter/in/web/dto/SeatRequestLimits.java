package com.aireak.inventory.adapter.in.web.dto;

/**
 * Upper bound on how many seat codes one hold or reserve request may carry.
 *
 * <p>booking-service already caps a booking at {@code Booking.MAX_TICKETS}, but that only guards
 * the booking path. {@code /hold} and {@code /reserve} are reached with an ordinary customer JWT —
 * {@code hold} is called by the frontend directly, one seat at a time, well before a booking
 * exists — so nothing made a caller go through booking-service to get here, and both accepted a
 * list of any length. One request could take a TTL hold on every seat of a showtime and close the
 * sale until the holds expired; the seat map is generated per showtime, so "every seat" is a
 * number the caller can simply read off {@code GET /seats} first.
 *
 * <p>Deliberately equal to {@code Booking.MAX_TICKETS}, never lower: booking-service reserves all
 * of a booking's seats in a single {@code /reserve} call, so anything tighter here would reject
 * bookings that booking-service's own domain rule allows, and the saga would fail after the
 * booking row was already written. Both are 8, the number the seat map has always shown the
 * customer.
 *
 * <p>The release paths ({@code DELETE /hold}, {@code /release}) are deliberately left uncapped.
 * They are compensating actions, and a bound there could strand seats that were held before this
 * limit existed — refusing to let go is a worse failure than letting go of too many at once.
 */
public final class SeatRequestLimits {

    public static final int MAX_SEATS_PER_REQUEST = 8;

    private SeatRequestLimits() {
    }
}
