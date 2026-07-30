package com.aireak.payment.application.port.out;

import com.aireak.common.exception.IdentityMismatchException;

/**
 * Outbound port: verifies that the caller (identified by their bearer token) actually owns the
 * booking they're trying to act on. Delegates to booking-service's own ownership-enforcing
 * {@code GET /api/v1/bookings/{bookingId}} endpoint — payment-service never duplicates that
 * logic, it just forwards the caller's original token and interprets the response code.
 *
 * @see com.aireak.payment.adapter.out.client.BookingOwnershipRestAdapter
 */
public interface BookingOwnershipPort {

    /**
     * Verifies the caller owns the given booking.
     *
     * @param bookingId          the booking to check ownership for
     * @param callerBearerToken  the caller's original JWT (forwarded as-is to booking-service)
     * @throws IdentityMismatchException if booking-service returns 403 or 404 (caller does not
     *                                    own the booking, or it doesn't exist)
     * @throws RuntimeException          if the booking-service call fails for any other reason
     */
    void verifyCallerOwnsBooking(String bookingId, String callerBearerToken);
}
