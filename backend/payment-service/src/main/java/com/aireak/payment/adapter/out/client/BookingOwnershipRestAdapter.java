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

/**
 * Calls booking-service's {@code GET /api/v1/bookings/{bookingId}} to verify the caller owns
 * the booking. That endpoint already enforces ownership (403 if the JWT subject doesn't match
 * the booking's customerId, 404 if the booking doesn't exist) — this adapter only interprets
 * the HTTP response code, never duplicates the ownership logic itself.
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

    @Value("${services.booking.base-url:http://localhost:8083}")
    private String baseUrl;

    @Override
    public void verifyCallerOwnsBooking(String bookingId, String callerBearerToken) {
        restClient.get()
                .uri(baseUrl + "/api/v1/bookings/{bookingId}", bookingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + callerBearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    log.debug("Booking ownership check failed: bookingId={}, status={}",
                            bookingId, response.getStatusCode());
                    throw new IdentityMismatchException(
                            "Caller does not own booking " + bookingId);
                })
                .toBodilessEntity();
    }
}
