package com.aireak.inventory.adapter.in.web;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.in.GetSeatingLayoutUseCase;
import com.aireak.inventory.application.port.in.HoldSeatsUseCase;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.UnholdSeatsUseCase;
import com.aireak.inventory.application.port.in.command.ConfirmSeatsCommand;
import com.aireak.inventory.application.port.in.command.ReleaseSeatsCommand;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
// The web slice registers every @ControllerAdvice, and InventoryOverloadExceptionHandler counts
// its rejections on a MeterRegistry — which @WebMvcTest does not auto-configure.
@Import(SimpleMeterRegistry.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret="
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
    private GetSeatingLayoutUseCase getSeatingLayoutUseCase;

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
        when(getSeatMapUseCase.getSeatMap(eq("showtime-1"), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/inventory/{showtimeId}/seats", "showtime-1")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getLayoutRejectsRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/{showtimeId}/layout", "showtime-1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(getSeatingLayoutUseCase);
    }

    @Test
    void getLayoutWithValidTokenReturns404WhenNotFound() throws Exception {
        when(getSeatingLayoutUseCase.getLayout("showtime-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/inventory/{showtimeId}/layout", "showtime-1")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNotFound());
    }

    // Pins the exact response shape (id/name/blocks/seatCodes) the frontend's SeatLayout type
    // depends on (see frontend/src/features/seats/types.ts) — a field rename here would silently
    // break the Section/Block picker without failing any TypeScript build.
    @Test
    void getLayoutWithValidTokenReturnsSectionsAndBlocksInFrontendShape() throws Exception {
        when(getSeatingLayoutUseCase.getLayout("showtime-1")).thenReturn(Optional.of(
                new GetSeatingLayoutUseCase.LayoutResult("showtime-1", List.of(
                        new GetSeatingLayoutUseCase.SectionSummary("VIP", List.of(
                                new GetSeatingLayoutUseCase.BlockSummary("A", List.of("A1", "A2"))))))));

        mockMvc.perform(get("/api/v1/inventory/{showtimeId}/layout", "showtime-1")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showtimeId").value("showtime-1"))
                .andExpect(jsonPath("$.sections[0].id").value("sec-vip"))
                .andExpect(jsonPath("$.sections[0].name").value("VIP"))
                .andExpect(jsonPath("$.sections[0].blocks[0].id").value("blk-vip-a"))
                .andExpect(jsonPath("$.sections[0].blocks[0].name").value("A"))
                .andExpect(jsonPath("$.sections[0].blocks[0].seatCodes[0]").value("A1"))
                .andExpect(jsonPath("$.sections[0].blocks[0].seatCodes[1]").value("A2"));
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

    // Confirm is only ever called by TicketInventoryRestAdapter#confirmReservation with a
    // minted internal-service token, never a forwarded customer token — a customer calling
    // it directly would finalize a sale without ever paying.
    @Test
    void confirmRejectsCustomerToken() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/confirm", "showtime-1")
                        .header("Authorization", "Bearer " + validToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"booking-1\",\"seatCodes\":[\"A1\"]}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(confirmSeatsUseCase);
    }

    @Test
    void confirmAllowsInternalServiceToken() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/confirm", "showtime-1")
                        .header("Authorization", "Bearer " + internalServiceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"booking-1\",\"seatCodes\":[\"A1\"]}"))
                .andExpect(status().isOk());

        verify(confirmSeatsUseCase).execute(new ConfirmSeatsCommand("showtime-1", "booking-1", List.of("A1")));
    }

    // Internal-service-token releases (e.g. the PAYMENT_FAILED-compensation path off a Kafka
    // listener thread) are trusted unconditionally — no requestingCustomerId on the command, so
    // SeatInventoryService skips the ownership check entirely.
    @Test
    void releaseWithInternalServiceTokenSkipsOwnershipCheck() throws Exception {
        mockMvc.perform(delete("/api/v1/inventory/{showtimeId}/reserve/{bookingId}?seatCodes=A1",
                        "showtime-1", "booking-1")
                        .header("Authorization", "Bearer " + internalServiceToken()))
                .andExpect(status().isOk());

        verify(releaseSeatsUseCase).execute(new ReleaseSeatsCommand("showtime-1", "booking-1", List.of("A1")));
    }

    // A customer-token release (the createBooking-compensation path) must carry the caller's own
    // id so SeatInventoryService can verify it actually owns the reservation being released,
    // instead of trusting the bookingId path variable alone — see SeatInventoryServiceTest for
    // the actual ownership-enforcement behavior.
    @Test
    void releaseWithCustomerTokenCarriesCallerIdForOwnershipCheck() throws Exception {
        String customerId = UUID.randomUUID().toString();

        mockMvc.perform(delete("/api/v1/inventory/{showtimeId}/reserve/{bookingId}?seatCodes=A1",
                        "showtime-1", "booking-1")
                        .header("Authorization", "Bearer " + customerToken(customerId)))
                .andExpect(status().isOk());

        verify(releaseSeatsUseCase).execute(
                new ReleaseSeatsCommand("showtime-1", "booking-1", List.of("A1"), customerId));
    }

    private String validToken() {
        return signedToken(UUID.randomUUID().toString(), null);
    }

    private String customerToken(String customerId) {
        return signedToken(customerId, null);
    }

    private String internalServiceToken() {
        return signedToken("booking-service", AuthenticatedUser.TOKEN_TYPE_INTERNAL_SERVICE);
    }

    private String signedToken(String subject, String tokenType) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            if (tokenType != null) {
                claims.claim("tokenType", tokenType);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }
}
