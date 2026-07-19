package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.TicketInventoryPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Outbound REST adapter: calls ticket-inventory-service.
 *
 * <p>Resilience4j (hexagonal rule — only this adapter layer uses these annotations):
 * <ul>
 *   <li>{@code @CircuitBreaker} — opens after N failures, fast-fail for duration</li>
 *   <li>{@code @Retry} — retries transient errors (connection timeout, 503)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketInventoryRestAdapter implements TicketInventoryPort {

    private final RestClient restClient;

    @Value("${services.ticket-inventory.base-url:http://localhost:8083}")
    private String baseUrl;

    @Override
    @CircuitBreaker(name = "ticket-inventory", fallbackMethod = "reserveSeatsFallback")
    @Retry(name = "ticket-inventory")
    public void reserveSeats(String showtimeId, String bookingId, List<String> seatCodes) {
        log.debug("Reserving seats: showtime={}, booking={}", showtimeId, bookingId);
        restClient.post()
                .uri(baseUrl + "/api/v1/inventory/{showtimeId}/reserve", showtimeId)
                .body(new ReservationRequest(bookingId, seatCodes))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    @CircuitBreaker(name = "ticket-inventory", fallbackMethod = "releaseSeatsFallback")
    @Retry(name = "ticket-inventory")
    public void releaseSeats(String showtimeId, String bookingId, List<String> seatCodes) {
        log.debug("Releasing seats: showtime={}, booking={}", showtimeId, bookingId);
        restClient.delete()
                .uri(baseUrl + "/api/v1/inventory/{showtimeId}/reserve/{bookingId}?seatCodes={codes}",
                        showtimeId, bookingId, String.join(",", seatCodes))
                .retrieve()
                .toBodilessEntity();
    }

    // Fallback: propagate as RuntimeException so saga compensates
    private void reserveSeatsFallback(String showtimeId, String bookingId,
                                       List<String> seatCodes, Throwable t) {
        log.error("Circuit open / retry exhausted for reserveSeats: {}", t.getMessage());
        throw new RuntimeException("Ticket inventory service unavailable", t);
    }

    private void releaseSeatsFallback(String showtimeId, String bookingId,
                                       List<String> seatCodes, Throwable t) {
        // Log and swallow — release is best-effort (timeout cleanup handles stragglers)
        log.error("Failed to release seats (non-critical): showtime={}, booking={}: {}",
                showtimeId, bookingId, t.getMessage());
    }

    /** Internal DTO for the REST call body. */
    record ReservationRequest(String bookingId, List<String> seatCodes) {}
}
