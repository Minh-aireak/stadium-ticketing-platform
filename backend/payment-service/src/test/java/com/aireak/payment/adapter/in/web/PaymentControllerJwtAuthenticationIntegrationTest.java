package com.aireak.payment.adapter.in.web;

import com.aireak.payment.application.port.in.GetPaymentUseCase;
import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.RetryPaymentUseCase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms {@code JwtAuthenticationFilter} (from common) is actually wired into this service's
 * filter chain — not just unit-tested in isolation. See {@code JwtAuthenticationFilterTest} in
 * the common module for the filter's own validation-logic coverage.
 */
@WebMvcTest(PaymentController.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients"
})
class PaymentControllerJwtAuthenticationIntegrationTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InitiatePaymentUseCase initiatePaymentUseCase;

    @MockitoBean
    private GetPaymentUseCase getPaymentUseCase;

    @MockitoBean
    private RetryPaymentUseCase retryPaymentUseCase;

    @Test
    void rejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{bookingId}", "nonexistent"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRequestWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{bookingId}", "nonexistent")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenReachesTheControllerWhichReturns404ForAnUnknownBooking() throws Exception {
        when(getPaymentUseCase.getByBookingId("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/payments/{bookingId}", "nonexistent")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNotFound());
    }

    private String validToken() {
        SecretKey secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
    }
}
