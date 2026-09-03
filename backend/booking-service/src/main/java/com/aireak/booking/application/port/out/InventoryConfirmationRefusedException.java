package com.aireak.booking.application.port.out;

/**
 * ticket-inventory-service will never sell these seats to this booking: they are SOLD to a
 * different one ({@code SeatAlreadySoldException}, 422), or the showtime has no seat inventory at
 * all ({@code SeatInventoryNotFoundException}, 404). Raised only by
 * {@code TicketInventoryRestAdapter#confirmReservation}, which runs after the customer has already
 * been charged and the booking is already CONFIRMED.
 *
 * <p>Deliberately NOT a {@code DomainException}. Nothing answers an HTTP request with it —
 * confirmReservation only ever runs on a Kafka listener thread or a scheduler thread — and giving
 * it the 422 that {@code GlobalExceptionHandler} attaches to every DomainException would invite
 * exactly that. It is a signal between two layers of this service, not a message to a customer.
 *
 * <p>Kept apart from {@link OutboundServiceUnavailableException}, which is the same call failing
 * for a reason that may clear on its own, for three separate reasons:
 *
 * <ul>
 *   <li>{@code BookingOrchestrationService#confirmInventoryReservation} records this one and stops,
 *       instead of leaving {@code inventoryConfirmed} false for
 *       {@code InventoryConfirmationReconciler} to re-ask every five minutes for the life of the
 *       row. The answer cannot change; only a human can resolve it.</li>
 *   <li>the {@code ticket-inventory} retry must not re-send it, for the same reason;</li>
 *   <li>the {@code ticket-inventory} circuit breaker must not count it. The reconciler re-asks
 *       every stuck booking on a fixed schedule, so refusals arrive in batches — enough of them to
 *       open a breaker that also guards reserveSeats, taking the buy path down over bookings that
 *       are already lost. This is why the conversion happens in the adapter method's own body
 *       rather than in its fallback: a fallback runs outside the circuit-breaker aspect, so a type
 *       introduced there is invisible to the breaker and the raw 4xx is what gets counted.</li>
 * </ul>
 *
 * <p>Both ignore lists name this type explicitly (see {@code application.yaml}).
 */
public class InventoryConfirmationRefusedException extends RuntimeException {

    public InventoryConfirmationRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
