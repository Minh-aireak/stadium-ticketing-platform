package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.InventoryConfirmationRefusedException;
import com.aireak.booking.application.port.out.OutboundServiceUnavailableException;
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
import java.util.Set;

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

    // The two statuses ticket-inventory-service uses to say it will not take these seats:
    //   422 — SeatsNotAvailableException / ShowtimeBookingClosedException on the reserve path,
    //         SeatAlreadySoldException on the confirm path. DomainExceptions, which
    //         GlobalExceptionHandler maps there.
    //   404 — SeatInventoryNotFoundException: the showtime has no seat inventory at all.
    // Both are the service answering about these seats, not the service failing, so both become a
    // named refusal — SeatReservationRejectedException before payment,
    // InventoryConfirmationRefusedException after it — rather than something that looks like an
    // outage: retrying cannot change either, and neither may count against a circuit breaker
    // protecting us from a service that is in fact healthy. The two types are separate because
    // what the saga does with the answer is: before payment it tells the customer, after payment
    // it has to record a booking only a human can now resolve.
    private static final Set<Integer> SEATS_REFUSED = Set.of(404, 422);

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
            if (!SEATS_REFUSED.contains(e.getStatusCode().value())) {
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
        try {
            // Always the internal service token: confirmReservation only ever runs from
            // PaymentResultConsumer (Kafka listener thread) or InventoryConfirmationReconciler
            // (scheduled thread) — never inside the original caller's HTTP request.
            restClient.post()
                    .uri(baseUrl + "/api/v1/inventory/{showtimeId}/confirm", showtimeId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalServiceTokenProvider.mintServiceToken())
                    .body(new ReservationRequest(bookingId, seatCodes))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // The same two statuses reserveSeats refuses on, meaning the same thing one step
            // later: these seats are not this booking's. 404 is a showtime with no inventory at
            // all; 422 is SeatAlreadySoldException — the hold lapsed and somebody else bought the
            // seat while this confirm was failing. Every other 4xx (403 on a misconfigured
            // internal token, 400 on a request shape that drifted) is left alone on purpose: it is
            // fixable without abandoning a booking that has already been paid for.
            //
            // Converted HERE and not in confirmReservationFallback, deliberately. The fallback
            // hangs off @Retry and so runs outside the circuit-breaker aspect; a type it
            // introduces is invisible to the breaker, which would go on counting the raw 4xx.
            // InventoryConfirmationReconciler re-asks every unresolved booking on a fixed
            // schedule, so refusals arrive in batches large enough to open a breaker that also
            // guards reserveSeats — taking the buy path down over bookings already lost. Same
            // placement, and the same reason, as reserveSeats' own conversion above.
            if (!SEATS_REFUSED.contains(e.getStatusCode().value())) {
                throw e;
            }
            throw new InventoryConfirmationRefusedException(refusalDetail(e), e);
        }
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
    // decides only what the CUSTOMER is told -- see the three branches below.
    private ReservedPrice reserveSeatsFallback(String showtimeId, String bookingId,
                                       List<String> seatCodes, Throwable t) {
        // A refusal travels unchanged: 422, naming which seats and why.
        if (t instanceof SeatReservationRejectedException refused) {
            throw refused;
        }
        // Any other 4xx is inventory answering about THIS request -- booking sent something it
        // will never accept. Waiting and retrying cannot help, so it must not be dressed up as a
        // temporary outage; it stays a 500, which is the honest report of a bug on this side.
        if (t instanceof HttpClientErrorException clientError) {
            throw clientError;
        }
        // Everything left is "no usable answer came back": timeout, reset, open circuit, full
        // bulkhead, or a 5xx that outlived the retries. This used to be a bare RuntimeException,
        // which GlobalExceptionHandler can only report as 500 "An unexpected error occurred" --
        // telling a customer the platform is broken when a dependency is merely down. The named
        // type is what lets BookingController answer 503 with a Retry-After instead.
        log.error("Circuit open / retry exhausted for reserveSeats: {}", t.getMessage());
        throw new OutboundServiceUnavailableException("Ticket inventory service unavailable", t);
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
    //
    // WHICH failure it was matters as much as that there was one, and until this method
    // distinguished them it did not survive: a permanent refusal and a service that was merely
    // restarting both left here as the same bare RuntimeException carrying the same words, so the
    // saga left both for a reconciler that re-asked the permanent one every five minutes for the
    // life of the row.
    private void confirmReservationFallback(String showtimeId, String bookingId,
                                             List<String> seatCodes, Throwable t) {
        // Travels unchanged, and is already logged by whoever records it — see
        // BookingOrchestrationService#recordInventorySaleRefused, which is the only caller that
        // can say what happens to the booking next.
        if (t instanceof InventoryConfirmationRefusedException refused) {
            throw refused;
        }
        log.error("Failed to confirm reservation after payment success — left for reconciliation: " +
                        "showtime={}, booking={}, seats={}: {}",
                showtimeId, bookingId, seatCodes, t.getMessage());
        // Named rather than bare for the same reason reserveSeatsFallback's is, one step earlier:
        // a type nothing can tell apart is a failure nothing can act on differently.
        throw new OutboundServiceUnavailableException(
                "Ticket inventory service unavailable for confirmReservation", t);
    }

    record ReservationRequest(String bookingId, List<String> seatCodes) {}
    record ReserveResponse(BigDecimal totalPrice, String currency) {}
}
