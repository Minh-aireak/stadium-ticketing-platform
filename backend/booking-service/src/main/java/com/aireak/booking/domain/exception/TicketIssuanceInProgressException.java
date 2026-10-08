package com.aireak.booking.domain.exception;

/**
 * A paid booking whose seats ticket-inventory-service has not finished selling to it yet, so they
 * cannot be cancelled yet — a 409, not a refusal. Returning the seats while the sale is still in
 * flight could let it land afterwards and mark them SOLD to a booking that no longer holds them,
 * where nothing would ever put them back on sale.
 *
 * <p>Deliberately not a {@link com.aireak.common.exception.DomainException}: that maps to 422,
 * which tells the caller the answer is final, while this one clears by itself within minutes.
 */
public class TicketIssuanceInProgressException extends RuntimeException {

    public TicketIssuanceInProgressException(String bookingId) {
        super("Tickets for booking " + bookingId + " are still being issued; try cancelling again shortly");
    }
}
