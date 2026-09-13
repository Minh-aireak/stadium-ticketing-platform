package com.aireak.booking.application.port.in;

import com.aireak.booking.domain.model.Booking;

/**
 * Inbound port: a customer gives up a booking of their own that has not been paid for.
 *
 * <p>The only status this accepts is PENDING_PAYMENT. DRAFT means the creation saga is still
 * running on another thread and will move the booking itself; CONFIRMED means money was taken,
 * and giving it back is a refund decision this platform does not yet let the customer make
 * alone. Cancelling an already CANCELLED booking is a no-op, so a double click or a retried
 * request answers the same way as the first.
 */
public interface CancelBookingUseCase {

    /**
     * @param bookingId             the booking to cancel
     * @param requestingCustomerId  the JWT-authenticated caller; must own the booking
     * @return the booking after the call — CANCELLED
     * @throws com.aireak.common.exception.ResourceNotFoundException   no such booking
     * @throws com.aireak.common.exception.IdentityMismatchException   the booking belongs to someone else
     * @throws com.aireak.booking.domain.exception.InvalidBookingStatusException DRAFT or CONFIRMED
     */
    Booking cancelBooking(String bookingId, String requestingCustomerId);
}
