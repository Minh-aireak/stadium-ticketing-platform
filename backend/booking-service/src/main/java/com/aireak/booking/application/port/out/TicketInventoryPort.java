package com.aireak.booking.application.port.out;

import java.math.BigDecimal;
import java.util.List;

// Outbound port: calls ticket-inventory-service via REST.
// Implemented by TicketInventoryRestAdapter with @CircuitBreaker/@Retry.
public interface TicketInventoryPort {

    // @return the authoritative total price for the reserved seats, computed server-side by
    // ticket-inventory-service from each seat's tier — the only value BookingOrchestrationService
    // may use to charge; a client-supplied amount is never trusted for that.
    BigDecimal reserveSeats(String showtimeId, String bookingId, List<String> seatCodes);
    void releaseSeats(String showtimeId, String bookingId, List<String> seatCodes);

    // Finalizes the seat sale on payment success — must be called or the Redis hold silently expires.
    void confirmReservation(String showtimeId, String bookingId, List<String> seatCodes);
}
