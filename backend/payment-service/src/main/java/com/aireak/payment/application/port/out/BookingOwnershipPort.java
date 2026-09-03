package com.aireak.payment.application.port.out;

import com.aireak.common.exception.IdentityMismatchException;

import java.math.BigDecimal;

/**
 * Outbound port: fetches the booking a caller is trying to pay for, having first proven the caller
 * owns it. Delegates to booking-service's own ownership-enforcing
 * {@code GET /api/v1/bookings/{bookingId}} endpoint — payment-service never duplicates that logic,
 * it just forwards the caller's original token and interprets the response.
 *
 * <p>Returns the booking rather than only asserting ownership, because ownership alone was never
 * enough: {@code PaymentController} took the charge amount straight from the request body, so a
 * caller who legitimately owned a booking could still initiate its payment for any amount they
 * chose. The authoritative amount lives on the booking (booking-service computes it server-side
 * from each seat's tier), and the only way for payment-service to check the request against it is
 * to read it back here.
 *
 * @see com.aireak.payment.adapter.out.client.BookingOwnershipRestAdapter
 */
public interface BookingOwnershipPort {

    /**
     * Fetches the booking, verifying the caller owns it.
     *
     * @param bookingId          the booking to fetch
     * @param callerBearerToken  the caller's original JWT (forwarded as-is to booking-service)
     * @return the booking's server-computed charge details
     * @throws IdentityMismatchException if booking-service returns 403 or 404 (caller does not
     *                                    own the booking, or it doesn't exist)
     * @throws RuntimeException          if the booking-service call fails for any other reason
     */
    OwnedBooking fetchOwnedBooking(String bookingId, String callerBearerToken);

    /**
     * The authoritative charge for a booking, as recorded by booking-service.
     *
     * <p>{@code amount}/{@code currency} may be null when talking to a booking-service instance
     * that predates this endpoint returning them; callers must treat that as "cannot verify"
     * rather than "verified" — see {@code PaymentController#requireAmountMatchesBooking}.
     */
    record OwnedBooking(String bookingId, String status, BigDecimal amount, String currency) {}
}
