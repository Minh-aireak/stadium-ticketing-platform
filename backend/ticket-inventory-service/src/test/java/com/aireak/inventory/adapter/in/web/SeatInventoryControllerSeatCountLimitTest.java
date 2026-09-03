package com.aireak.inventory.adapter.in.web;

import com.aireak.inventory.adapter.in.web.dto.SeatRequestLimits;
import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.in.GetSeatingLayoutUseCase;
import com.aireak.inventory.application.port.in.HoldSeatsUseCase;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.UnholdSeatsUseCase;
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
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /hold} and {@code /reserve} took a seat list of any length. Both are reached with an
 * ordinary customer JWT — the frontend calls {@code hold} itself, seat by seat — so nothing forced
 * a caller through booking-service, and {@code Booking.MAX_TICKETS} guarded only the booking path.
 * One request could put a TTL hold on every seat of a showtime and close the sale until the holds
 * expired.
 *
 * <p>The eight-seat limit in the browser is not a control: it is a constant in
 * {@code SeatSelectionPage}, and the endpoint is a plain authenticated POST.
 *
 * <p>Both bounds are asserted, not just the rejection. A cap that also turned away the largest
 * legitimate booking would break the saga after booking-service had already written the row.
 */
@WebMvcTest(SeatInventoryController.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret="
})
class SeatInventoryControllerSeatCountLimitTest {

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
    void holdRejectsMoreSeatsThanOneRequestMayCarry() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/hold", "showtime-1")
                        .header("Authorization", "Bearer " + customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(SeatRequestLimits.MAX_SEATS_PER_REQUEST + 1)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(holdSeatsUseCase);
    }

    @Test
    void holdStillAcceptsExactlyTheLimit() throws Exception {
        when(holdSeatsUseCase.execute(any())).thenReturn(new BigDecimal("100.00"));

        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/hold", "showtime-1")
                        .header("Authorization", "Bearer " + customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(SeatRequestLimits.MAX_SEATS_PER_REQUEST)))
                .andExpect(status().isOk());
    }

    @Test
    void reserveRejectsMoreSeatsThanOneRequestMayCarry() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/reserve", "showtime-1")
                        .header("Authorization", "Bearer " + customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(SeatRequestLimits.MAX_SEATS_PER_REQUEST + 1)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(reserveSeatsUseCase);
    }

    /**
     * booking-service reserves a whole booking in one call, and its own cap is
     * {@code Booking.MAX_TICKETS}. If this ever fails, the two limits have drifted apart and the
     * largest legitimate booking now dies mid-saga.
     */
    @Test
    void reserveStillAcceptsTheLargestBookingBookingServiceWillSend() throws Exception {
        when(reserveSeatsUseCase.execute(any())).thenReturn(new BigDecimal("100.00"));

        mockMvc.perform(post("/api/v1/inventory/{showtimeId}/reserve", "showtime-1")
                        .header("Authorization", "Bearer " + customerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reserveBody(SeatRequestLimits.MAX_SEATS_PER_REQUEST)))
                .andExpect(status().isOk());
    }

    private static String holdBody(int seatCount) {
        return "{\"seatCodes\":" + seatCodeArray(seatCount) + "}";
    }

    private static String reserveBody(int seatCount) {
        return "{\"bookingId\":\"booking-1\",\"seatCodes\":" + seatCodeArray(seatCount) + "}";
    }

    private static String seatCodeArray(int seatCount) {
        return IntStream.rangeClosed(1, seatCount)
                .mapToObj(i -> "\"A" + i + "\"")
                .toList()
                .toString();
    }

    private String customerToken() {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(UUID.randomUUID().toString())
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
