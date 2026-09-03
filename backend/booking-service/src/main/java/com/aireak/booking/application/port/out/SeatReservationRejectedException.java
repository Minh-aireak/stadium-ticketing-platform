package com.aireak.booking.application.port.out;

import com.aireak.common.exception.DomainException;

/**
 * ticket-inventory-service refused the reservation on its own terms — the seats are gone, or the
 * showtime's booking window has closed. A definite answer from a healthy service, not a failure to
 * reach one, which is the whole reason it is kept apart from
 * {@link OutboundServiceUnavailableException}:
 *
 * <ul>
 *   <li>the {@code ticket-inventory} circuit breaker must not count it, or ordinary contention for
 *       a seat on a hot match would open the breaker on the service that answered correctly — and
 *       an open breaker takes reserveSeats, releaseSeats and the post-payment confirmReservation
 *       with it;</li>
 *   <li>the {@code ticket-inventory} retry must not re-send it, since the answer cannot change;</li>
 *   <li>being a {@link DomainException}, {@code GlobalExceptionHandler} answers the customer 422
 *       rather than the 500 a bare {@code RuntimeException} used to earn them.</li>
 * </ul>
 *
 * <p>Both ignore lists name this type explicitly (see {@code application.yaml}).
 */
public class SeatReservationRejectedException extends DomainException {

    public SeatReservationRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
