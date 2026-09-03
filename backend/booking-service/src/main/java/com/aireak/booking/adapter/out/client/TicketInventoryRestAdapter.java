package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.SeatReservationRejectedException;
import com.aireak.booking.application.port.out.TicketInventoryPort;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outbound REST adapter: calls ticket-inventory-service. Resilience4j @CircuitBreaker/@Retry
 * live only at this adapter layer (hexagonal rule).
 *
 * <p>Every {@code fallbackMethod} below hangs off {@code @Retry}, not {@code @CircuitBreaker},
 * and has to. {@code FallbackExecutor} wraps the aspect it is declared on from the OUTSIDE and
 * {@code DefaultFallbackDecorator} catches {@code Throwable} — not just a rejected call — so a
 * fallback declared on {@code @CircuitBreaker} fired on the first failure of any kind. The Retry
 * aspect sits outside the CircuitBreaker aspect (orders 2147483642 vs 2147483643), so from there
 * on it only ever saw the fallback's replacement exception, and
 * {@code PredicateCreator.makePredicate} matches {@code ignoreExceptions} with a bare
 * {@code isAssignableFrom} and no cause traversal: every entry in this service's retry ignore list
 * was dead config. Declared here, the fallback runs where its own log line has always claimed it
 * did — once, after the retries are spent or the circuit is open.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketInventoryRestAdapter implements TicketInventoryPort {

    // Explicit qualifier: InfraConfig defines a second RestClient bean (paymentStatusRestClient),
    // so type alone no longer resolves unambiguously.
    @Qualifier("restClient")
    private final RestClient restClient;

    private final InternalServiceTokenProvider internalServiceTokenProvider;

    @Value("${services.ticket-inventory.base-url:http://localhost:8083}")
    private String baseUrl;

    // ticket-inventory-service answers 422 when it will not take these seats: its
    // SeatsNotAvailableException and ShowtimeBookingClosedException are DomainExceptions, which
    // GlobalExceptionHandler maps to 422. That is the service answering, not the service failing.
    private static final int SEATS_REFUSED = 422;

    @Override
    @Bulkhead(name = "ticket-inventory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "ticket-inventory")
    @Retry(name = "ticket-inventory", fallbackMethod = "reserveSeatsFallback")
    public ReservedPrice reserveSeats(String showtimeId, String bookingId, List<String> seatCodes) {
        log.debug("Reserving seats: showtime={}, booking={}", showtimeId, bookingId);
        // Idempotency-Key is informational only — ticket-inventory-service does not read this
        // header. What actually makes a @Retry re-send safe is that the hold it places is keyed
        // by this same bookingId, so SeatHoldPort#confirmHold recognises a hold this booking
        // already owns and treats the replay as success instead of a conflict. (It did not: a
        // replay used to come back 409 "seats not available" for the booking's own seats, which
        // cancelled the booking and left the seats locked out until the hold's TTL expired.)
        ReserveResponse response;
        try {
            response = restClient.post()
                    .uri(baseUrl + "/api/v1/inventory/{showtimeId}/reserve", showtimeId)
                    .header("Idempotency-Key", bookingId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationToken())
                    .body(new ReservationRequest(bookingId, seatCodes))
                    .retrieve()
                    .body(ReserveResponse.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != SEATS_REFUSED) {
                throw e;
            }
            throw new SeatReservationRejectedException(refusalDetail(e), e);
        }
        return new ReservedPrice(response.totalPrice(), response.currency());
    }

    @Override
    @Bulkhead(name = "ticket-inventory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "ticket-inventory")
    @Retry(name = "ticket-inventory", fallbackMethod = "releaseSeatsFallback")
    public void releaseSeats(String showtimeId, String bookingId, List<String> seatCodes) {
        log.debug("Releasing seats: showtime={}, booking={}", showtimeId, bookingId);
        restClient.delete()
                .uri(baseUrl + "/api/v1/inventory/{showtimeId}/reserve/{bookingId}?seatCodes={codes}",
                        showtimeId, bookingId, String.join(",", seatCodes))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationToken())
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    @Bulkhead(name = "ticket-inventory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "ticket-inventory")
    @Retry(name = "ticket-inventory", fallbackMethod = "confirmReservationFallback")
    public void confirmReservation(String showtimeId, String bookingId, List<String> seatCodes) {
        log.debug("Confirming reservation: showtime={}, booking={}", showtimeId, bookingId);
        // Always the internal service token: confirmReservation only ever runs from
        // PaymentResultConsumer (Kafka listener thread) or InventoryConfirmationReconciler
        // (scheduled thread) — never inside the original caller's HTTP request.
        restClient.post()
                .uri(baseUrl + "/api/v1/inventory/{showtimeId}/confirm", showtimeId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalServiceTokenProvider.mintServiceToken())
                .body(new ReservationRequest(bookingId, seatCodes))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Reads ticket-inventory-service's own customer-facing sentence off the ProblemDetail body
     * rather than replacing it: "seats not available" and "booking is closed for this showtime"
     * are different things for a customer to be told, and both arrive as 422.
     */
    private String refusalDetail(HttpClientErrorException e) {
        try {
            ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
            if (problem != null && problem.getDetail() != null && !problem.getDetail().isBlank()) {
                return problem.getDetail();
            }
        } catch (RuntimeException conversionFailure) {
            log.debug("ticket-inventory-service refused the reservation with a body this could not read: {}",
                    conversionFailure.getMessage());
        }
        return "The selected seats are no longer available";
    }

    // Forwards the original caller's JWT when this call happens inside their HTTP request
    // (reserveSeats, and releaseSeats' createBooking-compensation path); falls back to a minted
    // internal service token when it doesn't (releaseSeats' PAYMENT_FAILED-compensation path,
    // triggered from a Kafka listener thread with no AuthenticatedUserContext set).
    private String authorizationToken() {
        return AuthenticatedUserContext.get().map(AuthenticatedUser::token)
                .orElseGet(internalServiceTokenProvider::mintServiceToken);
    }

    // Fallback: the saga compensates on any exception out of here, so what this chooses to throw
    // decides only what the CUSTOMER is told -- see the two branches below.
    private ReservedPrice reserveSeatsFallback(String showtimeId, String bookingId,
                                       List<String> seatCodes, Throwable t) {
        // A refusal travels unchanged. The saga compensates either way, but only this one may
        // reach the customer as a 422 saying what happened -- GlobalExceptionHandler maps a
        // DomainException to 422 and everything else to a 500 "An unexpected error occurred".
        if (t instanceof SeatReservationRejectedException refused) {
            throw refused;
        }
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
    record ReserveResponse(BigDecimal totalPrice, String currency) {}
}
