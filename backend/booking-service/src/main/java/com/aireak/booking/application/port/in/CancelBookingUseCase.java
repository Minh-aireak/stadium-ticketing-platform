package com.aireak.booking.application.port.in;

import com.aireak.booking.domain.model.Booking;

/**
 * Inbound port: a customer cancels seats of a booking of their own — all of them, or some.
 *
 * <p>An unpaid (PENDING_PAYMENT) booking can only be cancelled as a whole; there is nothing to
 * refund. A paid (CONFIRMED) booking can be cancelled seat by seat until 24 hours before kickoff
 * (configurable): each cancelled seat goes back on sale and its price is refunded in full. DRAFT is
 * refused — the creation saga still owns it. Cancelling seats that are already cancelled is a
 * no-op, so a double click or a retried request answers the same way as the first.
 */
public interface CancelBookingUseCase {

    /**
     * @return the booking after the call
     * @throws com.aireak.common.exception.ResourceNotFoundException   no such booking
     * @throws com.aireak.common.exception.IdentityMismatchException   the booking belongs to someone else
     * @throws com.aireak.booking.domain.exception.InvalidBookingStatusException DRAFT
     * @throws com.aireak.booking.domain.exception.SeatCancellationException     seats not in the booking,
     *         or part of an unpaid booking
     * @throws com.aireak.booking.domain.exception.CancellationWindowClosedException past the deadline
     * @throws com.aireak.booking.domain.exception.TicketIssuanceInProgressException paid, but the seat
     *         sale is still being finalized
     * @throws com.aireak.booking.application.service.CancellationInProgressException another cancel of
     *         this booking is running right now
     * @throws com.aireak.booking.domain.exception.CancellationConflictException the booking changed
     *         under the request
     */
    Booking cancelBooking(CancelBookingCommand command);
}
