package com.aireak.booking.application.port.out;

import java.util.List;

// Outbound port: calls ticket-inventory-service via REST.
// Implemented by TicketInventoryRestAdapter with @CircuitBreaker/@Retry.
public interface TicketInventoryPort {
    void reserveSeats(String showtimeId, String bookingId, List<String> seatCodes);
    void releaseSeats(String showtimeId, String bookingId, List<String> seatCodes);

    // Finalizes the seat sale on payment success — must be called or the Redis hold silently expires.
    void confirmReservation(String showtimeId, String bookingId, List<String> seatCodes);
}
