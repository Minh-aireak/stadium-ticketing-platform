package com.aireak.booking.adapter.in.web;

import com.aireak.booking.application.port.in.CreateBookingUseCase;
import com.aireak.booking.application.port.in.GetBookingUseCase;
import com.aireak.booking.application.port.in.dto.BookingCreationResult;
import com.aireak.booking.domain.model.BookingStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        "jwt.audience=stadium-clients"
})
class BookingControllerJwtAuthenticationIntegrationTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    // Not @Autowired: the @WebMvcTest slice doesn't reliably expose the app's own ObjectMapper
    // bean, and this only needs plain, uncustomized JSON serialization of the request DTO anyway.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateBookingUseCase createBookingUseCase;

    @MockitoBean
    private GetBookingUseCase getBookingUseCase;

    @Test
    void rejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/{id}", "nonexistent"))
                .andExpect(status().isUnauthorized());
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
    void rejectsCreateBookingWhenBodyCustomerIdDoesNotMatchTheAuthenticatedCaller() throws Exception {
        String authenticatedAccountId = UUID.randomUUID().toString();
        String someoneElsesAccountId = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(new BookingController.CreateBookingRequest(
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
        String body = objectMapper.writeValueAsString(new BookingController.CreateBookingRequest(
                accountId, "showtime-1", List.of("A1"), new BigDecimal("50.00"), "USD"));
        when(createBookingUseCase.createBooking(any(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new BookingCreationResult(
                        "booking-1", BookingStatus.PENDING_PAYMENT));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + validToken(accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String validToken(String subject) {
        SecretKey secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
    }
}
