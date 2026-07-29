package com.aireak.inventory.adapter.in.web;

import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.in.HoldSeatsUseCase;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.UnholdSeatsUseCase;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms {@code JwtAuthenticationFilter} (from common) is actually wired into this service's
 * filter chain — not just unit-tested in isolation. See {@code JwtAuthenticationFilterTest} in
 * the common module for the filter's own validation-logic coverage.
 *
 * <p>Uses an intentionally invalid body ({@code {}}) so a valid token proves the request reached
 * {@code @Valid} argument resolution (400) without needing Redis/DB up for a real reservation.
 */
@WebMvcTest(SeatInventoryController.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients"
})
class SeatInventoryControllerJwtAuthenticationIntegrationTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReserveSeatsUseCase reserveSeatsUseCase;

    @MockitoBean
    private ReleaseSeatsUseCase releaseSeatsUseCase;

    @MockitoBean
    private ConfirmSeatsUseCase confirmSeatsUseCase;

    @MockitoBean
    private GetSeatMapUseCase getSeatMapUseCase;

    @MockitoBean
    private HoldSeatsUseCase holdSeatsUseCase;

    @MockitoBean
    private UnholdSeatsUseCase unholdSeatsUseCase;

    @Test
    void rejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/reserve", "showtime-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reserveSeatsUseCase);
    }

    @Test
    void holdRejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/hold", "showtime-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(holdSeatsUseCase);
    }

    @Test
    void unholdRejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(delete("/api/v1/inventory/{showtimeId}/hold?seatCodes=A1", "showtime-1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(unholdSeatsUseCase);
    }

    @Test
    void getSeatMapRejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/{showtimeId}/seats", "showtime-1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(getSeatMapUseCase);
    }

    @Test
    void getSeatMapWithValidTokenReturns404WhenNotFound() throws Exception {
        when(getSeatMapUseCase.getSeatMap("showtime-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/inventory/{showtimeId}/seats", "showtime-1")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsRequestWithInvalidToken() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/reserve", "showtime-1")
                        .header("Authorization", "Bearer not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenReachesTheControllerWhichRejectsTheInvalidBody() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/reserve", "showtime-1")
                        .header("Authorization", "Bearer " + validToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
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
