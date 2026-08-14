package com.aireak.booking.adapter.in.web;

import com.aireak.booking.application.port.in.CreateBookingUseCase;
import com.aireak.booking.application.port.in.GetBookingUseCase;
import com.aireak.booking.application.port.in.ListBookingsUseCase;
import com.aireak.booking.application.port.in.dto.BookingCreationResult;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.booking.domain.model.SeatSelection;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms {@code JwtAuthenticationFilter} (from common) is actually wired into this service's
 * filter chain — not just unit-tested in isolation. See {@code JwtAuthenticationFilterTest} in
 * the common module for the filter's own validation-logic coverage.
 */
@WebMvcTest(BookingController.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret="
})
class BookingControllerJwtAuthenticationIntegrationTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    // Not @Autowired: the @WebMvcTest slice doesn't reliably expose the app's own ObjectMapper
    // bean, and this only needs plain, uncustomized JSON serialization of the request DTO anyway.
    // Jackson 3: use JsonMapper.builder() — ObjectMapper is immutable and constructed via builder.
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateBookingUseCase createBookingUseCase;

    @MockitoBean
    private GetBookingUseCase getBookingUseCase;

    @MockitoBean
    private ListBookingsUseCase listBookingsUseCase;

    @Test
    void rejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/{id}", "nonexistent"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsListMineRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/bookings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listMineScopesToTheAuthenticatedCallerNotAClientSuppliedId() throws Exception {
        String accountId = UUID.randomUUID().toString();
        when(listBookingsUseCase.listByCustomer(accountId, 0, 20))
                .thenReturn(new ListBookingsUseCase.BookingPage(List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/v1/bookings")
                        .header("Authorization", "Bearer " + validToken(accountId)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsRequestWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/{id}", "nonexistent")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenReachesTheControllerWhichReturns404ForAnUnknownBooking() throws Exception {
        when(getBookingUseCase.getBooking("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/bookings/{id}", "nonexistent")
                        .header("Authorization", "Bearer " + validToken(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsGetBookingWhenOwnerDoesNotMatchTheAuthenticatedCaller() throws Exception {
        String ownerAccountId = UUID.randomUUID().toString();
        String someoneElsesAccountId = UUID.randomUUID().toString();
        Booking booking = Booking.reconstitute("booking-1", ownerAccountId, "owner@example.com", "showtime-1",
                new SeatSelection(List.of("A1")), BookingAmount.of(new BigDecimal("50.00"), "USD"),
                BookingStatus.PENDING_PAYMENT, Instant.now(), null, 0L, false);
        when(getBookingUseCase.getBooking("booking-1")).thenReturn(Optional.of(booking));

        mockMvc.perform(get("/api/v1/bookings/{id}", "booking-1")
                        .header("Authorization", "Bearer " + validToken(someoneElsesAccountId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsGetBookingWhenOwnerMatchesTheAuthenticatedCaller() throws Exception {
        String ownerAccountId = UUID.randomUUID().toString();
        Booking booking = Booking.reconstitute("booking-1", ownerAccountId, "owner@example.com", "showtime-1",
                new SeatSelection(List.of("A1")), BookingAmount.of(new BigDecimal("50.00"), "USD"),
                BookingStatus.PENDING_PAYMENT, Instant.now(), null, 0L, false);
        when(getBookingUseCase.getBooking("booking-1")).thenReturn(Optional.of(booking));

        mockMvc.perform(get("/api/v1/bookings/{id}", "booking-1")
                        .header("Authorization", "Bearer " + validToken(ownerAccountId)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsCreateBookingWhenBodyCustomerIdDoesNotMatchTheAuthenticatedCaller() throws Exception {
        String authenticatedAccountId = UUID.randomUUID().toString();
        String someoneElsesAccountId = UUID.randomUUID().toString();
        String body = jsonMapper.writeValueAsString(new BookingController.CreateBookingRequest(
                someoneElsesAccountId, "showtime-1", List.of("A1"), new BigDecimal("50.00"), "USD"));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + validToken(authenticatedAccountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsCreateBookingWhenBodyCustomerIdMatchesTheAuthenticatedCaller() throws Exception {
        String accountId = UUID.randomUUID().toString();
        String body = jsonMapper.writeValueAsString(new BookingController.CreateBookingRequest(
                accountId, "showtime-1", List.of("A1"), new BigDecimal("50.00"), "USD"));
        when(createBookingUseCase.createBooking(any(), anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(new BookingCreationResult(
                        "booking-1", BookingStatus.PENDING_PAYMENT));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + validToken(accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String validToken(String subject) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }
}
