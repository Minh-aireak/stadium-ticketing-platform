package com.aireak.payment.adapter.out.client;

import com.aireak.common.exception.IdentityMismatchException;
import com.aireak.payment.application.port.out.BookingOwnershipPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Calls booking-service's {@code GET /api/v1/bookings/{bookingId}} to fetch a booking the caller
 * owns. That endpoint already enforces ownership (403 if the JWT subject doesn't match the
 * booking's customerId, 404 if the booking doesn't exist) — this adapter only interprets the HTTP
 * response, never duplicates the ownership logic itself.
 *
 * <p>Forwards the caller's <em>original</em> bearer token, not an internal-service token —
 * the booking-service endpoint needs to see the real end-user identity to perform its own
 * ownership check.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingOwnershipRestAdapter implements BookingOwnershipPort {

    private final RestClient restClient;

    // 8086 is booking-service (application.yaml, server.port). 8083 -- which this used to name --
    // is ticket-inventory-service.
    @Value("${services.booking.base-url:http://localhost:8086}")
    private String baseUrl;

    @Override
    public OwnedBooking fetchOwnedBooking(String bookingId, String callerBearerToken) {
        BookingResponse response = restClient.get()
                .uri(baseUrl + "/api/v1/bookings/{bookingId}", bookingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerBearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, clientResponse) -> {
                    log.debug("Booking ownership check failed: bookingId={}, status={}",
                            bookingId, clientResponse.getStatusCode());
                    throw new IdentityMismatchException(
                            "Caller does not own booking " + bookingId);
                })
                .body(BookingResponse.class);

        if (response == null) {
            // A 2xx with no body is not an ownership failure but is not a usable answer either;
            // failing here is what keeps an unverifiable amount from being charged.
            throw new IllegalStateException("booking-service returned an empty body for booking " + bookingId);
        }
        return new OwnedBooking(response.bookingId(), response.status(), response.amount(), response.currency());
    }

    // amount/currency are absent on a booking-service instance older than the endpoint change that
    // added them; Jackson leaves them null and the caller treats null as "cannot verify".
    record BookingResponse(String bookingId, String status, BigDecimal amount, String currency) {}
}
