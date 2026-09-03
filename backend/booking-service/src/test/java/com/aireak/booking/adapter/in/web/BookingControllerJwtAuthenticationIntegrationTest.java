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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void rejectsCreateBookingWhenASeatCodeIsNotAWellFormedSeatCode() throws Exception {
        String accountId = UUID.randomUUID().toString();
        // Authenticated, and the body's customerId is the caller's own, so the only thing left
        // that can reject this is the per-element constraint on seatCodes. Before it existed,
        // "not-a-seat" reached the use case and became a persisted booking.
        String body = jsonMapper.writeValueAsString(new BookingController.CreateBookingRequest(
                accountId, "showtime-1", List.of("A1", "not-a-seat"), new BigDecimal("50.00"), "USD"));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + validToken(accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(createBookingUseCase);
    }

    /**
     * An Idempotency-Key is whatever the client sent, and both stores keyed off it —
     * {@code booking:idempotency:<key>} in Redis and the {@code ux_bookings_idempotency_key}
     * unique index — were a single namespace shared by every customer on the platform. A second
     * customer presenting a key a first customer had already completed was handed the FIRST
     * customer's bookingId and status as a 201, and their own booking was never created. The
     * caller's own account id is the only thing that can separate the two, and it is not the
     * client's to supply.
     */
    @Test
    void theIdempotencyKeyIsScopedToTheCallerSoTwoCustomersCannotShareOne() throws Exception {
        String firstAccountId = UUID.randomUUID().toString();
        String secondAccountId = UUID.randomUUID().toString();
        when(createBookingUseCase.createBooking(any(), anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(new BookingCreationResult("booking-1", BookingStatus.PENDING_PAYMENT));

        postBookingWithIdempotencyKey(firstAccountId, "shared-key");
        postBookingWithIdempotencyKey(secondAccountId, "shared-key");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(createBookingUseCase, times(2)).createBooking(
                keys.capture(), anyString(), any(), anyString(), any(), any(), anyString());
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
        assertThat(keys.getAllValues().get(0)).contains(firstAccountId);
        assertThat(keys.getAllValues().get(1)).contains(secondAccountId);
    }

    /** No header, no key — the saga must still see null, not an account id on its own. */
    @Test
    void aRequestWithoutAnIdempotencyKeyStillPassesNoneThrough() throws Exception {
        String accountId = UUID.randomUUID().toString();
        when(createBookingUseCase.createBooking(any(), anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(new BookingCreationResult("booking-1", BookingStatus.PENDING_PAYMENT));
        String body = jsonMapper.writeValueAsString(new BookingController.CreateBookingRequest(
                accountId, "showtime-1", List.of("A1"), new BigDecimal("50.00"), "USD"));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + validToken(accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        verify(createBookingUseCase).createBooking(
                isNull(), anyString(), any(), anyString(), any(), any(), anyString());
    }

    /**
     * An empty header reached the DB as {@code ''}, and the partial unique index treats that as a
     * value like any other — so the second customer to send one collided with the first.
     */
    @Test
    void anEmptyIdempotencyKeyHeaderCountsAsNoKeyAtAll() throws Exception {
        String accountId = UUID.randomUUID().toString();
        when(createBookingUseCase.createBooking(any(), anyString(), any(), anyString(), any(), any(), anyString()))
                .thenReturn(new BookingCreationResult("booking-1", BookingStatus.PENDING_PAYMENT));
        String body = jsonMapper.writeValueAsString(new BookingController.CreateBookingRequest(
                accountId, "showtime-1", List.of("A1"), new BigDecimal("50.00"), "USD"));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + validToken(accountId))
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        verify(createBookingUseCase).createBooking(
                isNull(), anyString(), any(), anyString(), any(), any(), anyString());
    }

    /**
     * The scoped key has to fit bookings.idempotency_key (VARCHAR(255)) with a 36-char account id
     * and a separator in front of it. Over-long is a bad request, not a 409 manufactured at commit
     * by a "value too long" the handler can only describe as a conflict with existing data.
     */
    @Test
    void rejectsAnIdempotencyKeyTooLongToStoreAlongsideTheAccountId() throws Exception {
        String accountId = UUID.randomUUID().toString();
        String body = jsonMapper.writeValueAsString(new BookingController.CreateBookingRequest(
                accountId, "showtime-1", List.of("A1"), new BigDecimal("50.00"), "USD"));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + validToken(accountId))
                        .header("Idempotency-Key", "k".repeat(201))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(createBookingUseCase);
    }

    private void postBookingWithIdempotencyKey(String accountId, String idempotencyKey) throws Exception {
        String body = jsonMapper.writeValueAsString(new BookingController.CreateBookingRequest(
                accountId, "showtime-1", List.of("A1"), new BigDecimal("50.00"), "USD"));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + validToken(accountId))
                        .header("Idempotency-Key", idempotencyKey)
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
