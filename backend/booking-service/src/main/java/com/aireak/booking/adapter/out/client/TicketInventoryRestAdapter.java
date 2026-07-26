package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.TicketInventoryPort;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

// Outbound REST adapter: calls ticket-inventory-service. Resilience4j @CircuitBreaker/@Retry
// live only at this adapter layer (hexagonal rule).
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketInventoryRestAdapter implements TicketInventoryPort {

    // Explicit qualifier: InfraConfig defines a second RestClient bean (paymentStatusRestClient),
    // so type alone no longer resolves unambiguously.
    @Qualifier("restClient")
    private final RestClient restClient;

    @Value("${services.ticket-inventory.base-url:http://localhost:8083}")
    private String baseUrl;

    @Override
    @Bulkhead(name = "ticket-inventory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "ticket-inventory", fallbackMethod = "reserveSeatsFallback")
    @Retry(name = "ticket-inventory")
    public void reserveSeats(String showtimeId, String bookingId, List<String> seatCodes) {
        log.debug("Reserving seats: showtime={}, booking={}", showtimeId, bookingId);
        // bookingId doubles as the Idempotency-Key so @Retry re-sends dedupe instead of
        // reserving the seats twice.
        restClient.post()
                .uri(baseUrl + "/api/v1/inventory/{showtimeId}/reserve", showtimeId)
                .header("Idempotency-Key", bookingId)
                .body(new ReservationRequest(bookingId, seatCodes))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    @Bulkhead(name = "ticket-inventory", type = Bulkhead.Type.SEMAPHORE)
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

    @Override
    @Bulkhead(name = "ticket-inventory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "ticket-inventory", fallbackMethod = "confirmReservationFallback")
    @Retry(name = "ticket-inventory")
    public void confirmReservation(String showtimeId, String bookingId, List<String> seatCodes) {
        log.debug("Confirming reservation: showtime={}, booking={}", showtimeId, bookingId);
        restClient.post()
                .uri(baseUrl + "/api/v1/inventory/{showtimeId}/confirm", showtimeId)
                .body(new ReservationRequest(bookingId, seatCodes))
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

    // Rethrows (unlike releaseSeatsFallback) so BookingOrchestrationService#confirmInventoryReservation
    // can tell the call failed and leave inventoryConfirmed=false — InventoryConfirmationReconciler
    // is the actual backstop; without a signal here it would never know to retry, and the Redis
    // hold would just expire via TTL despite payment having succeeded.
    private void confirmReservationFallback(String showtimeId, String bookingId,
                                             List<String> seatCodes, Throwable t) {
        log.error("Failed to confirm reservation after payment success — left for reconciliation: " +
                        "showtime={}, booking={}, seats={}: {}",
                showtimeId, bookingId, seatCodes, t.getMessage());
        throw new RuntimeException("Ticket inventory service unavailable for confirmReservation", t);
    }

    record ReservationRequest(String bookingId, List<String> seatCodes) {}
}
