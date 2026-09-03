package com.aireak.booking.application.port.out;

import java.math.BigDecimal;
import java.util.List;

// Outbound port: calls ticket-inventory-service via REST.
// Implemented by TicketInventoryRestAdapter with @CircuitBreaker/@Retry.
public interface TicketInventoryPort {

    // @return the authoritative price for the reserved seats, computed server-side by
    // ticket-inventory-service from each seat's tier — the only value BookingOrchestrationService
    // may use to charge; a client-supplied amount is never trusted for that.
    ReservedPrice reserveSeats(String showtimeId, String bookingId, List<String> seatCodes);
    void releaseSeats(String showtimeId, String bookingId, List<String> seatCodes);

    /**
     * A quoted price and the currency it is quoted in, together.
     *
     * <p>The currency travels with the amount rather than being taken from the client's request,
     * because it is not a label: payment-service multiplies by 100 for some currencies and not
     * others ({@code StripeGatewayAdapter#toSmallestUnit}), so whoever names the currency for a
     * given number is choosing what that number costs. Only the service that computed the price
     * knows what it is denominated in.
     */
    record ReservedPrice(BigDecimal amount, String currency) {}

    // Finalizes the seat sale on payment success — must be called or the Redis hold silently expires.
    void confirmReservation(String showtimeId, String bookingId, List<String> seatCodes);
}
