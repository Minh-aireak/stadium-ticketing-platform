package com.aireak.payment.adapter.in.web;

import com.aireak.common.exception.IdentityMismatchException;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.payment.application.port.in.GetPaymentUseCase;
import com.aireak.payment.application.port.in.InitiatePaymentUseCase;
import com.aireak.payment.application.port.in.RefundPaymentUseCase;
import com.aireak.payment.application.port.in.RetryPaymentUseCase;
import com.aireak.payment.application.port.out.BookingOwnershipPort;
import com.aireak.payment.domain.exception.DuplicatePaymentException;
import com.aireak.payment.domain.model.Payment;
import com.aireak.payment.domain.model.PaymentStatus;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms {@code JwtAuthenticationFilter} (from common) is actually wired into this service's
 * filter chain — not just unit-tested in isolation. See {@code JwtAuthenticationFilterTest} in
 * the common module for the filter's own validation-logic coverage.
 *
 * <p>Also validates the booking-ownership guard on all three payment endpoints: customer tokens
 * must pass the {@code BookingOwnershipPort} check, while internal-service tokens bypass it
 * entirely (reconciliation jobs / booking-service saga steps).
 */
@WebMvcTest(PaymentController.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "jwt.previous-secret=",
        "jwt.internal-secret="
})
class PaymentControllerJwtAuthenticationIntegrationTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    private static final String CUSTOMER_A_ID = UUID.randomUUID().toString();
    private static final String BOOKING_ID = "booking-1";
    private static final String PAYMENT_ID = "payment-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InitiatePaymentUseCase initiatePaymentUseCase;

    @MockitoBean
    private GetPaymentUseCase getPaymentUseCase;

    @MockitoBean
    private RetryPaymentUseCase retryPaymentUseCase;

    @MockitoBean
    private RefundPaymentUseCase refundPaymentUseCase;

    @MockitoBean
    private BookingOwnershipPort bookingOwnershipPort;

    // ---------------------------------------------------------------
    // JWT authentication (existing coverage)
    // ---------------------------------------------------------------

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
        when(bookingOwnershipPort.fetchOwnedBooking(anyString(), anyString())).thenReturn(ownedBooking());
        when(getPaymentUseCase.getByBookingId("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/payments/{bookingId}", "nonexistent")
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------
    // Booking-ownership enforcement: initiate
    // ---------------------------------------------------------------

    @Test
    void initiateReturns403WhenCustomerDoesNotOwnBooking() throws Exception {
        doThrow(new IdentityMismatchException("Caller does not own booking " + BOOKING_ID))
                .when(bookingOwnershipPort).fetchOwnedBooking(eq(BOOKING_ID), anyString());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","amount":"50.00","currency":"USD"}
                                """.formatted(BOOKING_ID)))
                .andExpect(status().isForbidden());

        verify(initiatePaymentUseCase, never()).execute(any());
    }

    @Test
    void initiateSucceedsWhenCustomerOwnsBooking() throws Exception {
        when(bookingOwnershipPort.fetchOwnedBooking(eq(BOOKING_ID), anyString())).thenReturn(ownedBooking());
        when(initiatePaymentUseCase.execute(any())).thenReturn(PAYMENT_ID);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","amount":"50.00","currency":"USD"}
                                """.formatted(BOOKING_ID)))
                .andExpect(status().isCreated());

        verify(initiatePaymentUseCase).execute(any());
    }

    @Test
    void initiateRejectsAnAmountThatUndercutsTheBooking() throws Exception {
        when(bookingOwnershipPort.fetchOwnedBooking(eq(BOOKING_ID), anyString())).thenReturn(ownedBooking());

        // Owning the booking is not a licence to name the price: a caller who reaches this
        // endpoint directly (booking-service's own Step 4 having failed) could otherwise pay a
        // token amount for a booking priced at 50.00 and still have it confirmed.
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","amount":"0.01","currency":"USD"}
                                """.formatted(BOOKING_ID)))
                .andExpect(status().isUnprocessableEntity());

        verify(initiatePaymentUseCase, never()).execute(any());
    }

    @Test
    void initiateRejectsACurrencyThatDiffersFromTheBooking() throws Exception {
        when(bookingOwnershipPort.fetchOwnedBooking(eq(BOOKING_ID), anyString())).thenReturn(ownedBooking());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","amount":"50.00","currency":"VND"}
                                """.formatted(BOOKING_ID)))
                .andExpect(status().isUnprocessableEntity());

        verify(initiatePaymentUseCase, never()).execute(any());
    }

    @Test
    void initiateAcceptsTheSameAmountWrittenWithADifferentScale() throws Exception {
        when(bookingOwnershipPort.fetchOwnedBooking(eq(BOOKING_ID), anyString())).thenReturn(ownedBooking());
        when(initiatePaymentUseCase.execute(any())).thenReturn(PAYMENT_ID);

        // 50 and 50.00 are the same money; BigDecimal.equals would disagree, hence compareTo.
        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","amount":"50","currency":"USD"}
                                """.formatted(BOOKING_ID)))
                .andExpect(status().isCreated());
    }

    @Test
    void initiateReturnsAcceptedWhenAnIdempotentPaymentAttemptIsStillInProgress() throws Exception {
        when(bookingOwnershipPort.fetchOwnedBooking(eq(BOOKING_ID), anyString())).thenReturn(ownedBooking());
        when(initiatePaymentUseCase.execute(any())).thenThrow(
                new DuplicatePaymentException("Payment for booking " + BOOKING_ID +
                        " is already being processed"));

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","amount":"50.00","currency":"USD"}
                                """.formatted(BOOKING_ID)))
                .andExpect(status().isAccepted());

        verify(initiatePaymentUseCase).execute(any());
    }

    @Test
    void initiateBypassesOwnershipCheckForInternalServiceToken() throws Exception {
        when(initiatePaymentUseCase.execute(any())).thenReturn(PAYMENT_ID);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + internalServiceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":"%s","amount":"50.00","currency":"USD"}
                                """.formatted(BOOKING_ID)))
                .andExpect(status().isCreated());

        verify(bookingOwnershipPort, never()).fetchOwnedBooking(anyString(), anyString());
    }

    // ---------------------------------------------------------------
    // Booking-ownership enforcement: getByBookingId
    // ---------------------------------------------------------------

    @Test
    void getByBookingIdReturns403WhenCustomerDoesNotOwnBooking() throws Exception {
        doThrow(new IdentityMismatchException("Caller does not own booking " + BOOKING_ID))
                .when(bookingOwnershipPort).fetchOwnedBooking(eq(BOOKING_ID), anyString());

        mockMvc.perform(get("/api/v1/payments/{bookingId}", BOOKING_ID)
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID)))
                .andExpect(status().isForbidden());

        verify(getPaymentUseCase, never()).getByBookingId(anyString());
    }

    @Test
    void getByBookingIdSucceedsWhenCustomerOwnsBooking() throws Exception {
        when(bookingOwnershipPort.fetchOwnedBooking(eq(BOOKING_ID), anyString())).thenReturn(ownedBooking());
        Payment payment = Payment.reconstitute(PAYMENT_ID, BOOKING_ID, "buyer@example.com", new BigDecimal("50.00"), "USD",
                PaymentStatus.SUCCEEDED, "gw-tx-1", null, Instant.now(), 1L);
        when(getPaymentUseCase.getByBookingId(BOOKING_ID)).thenReturn(Optional.of(payment));

        mockMvc.perform(get("/api/v1/payments/{bookingId}", BOOKING_ID)
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void getByBookingIdBypassesOwnershipCheckForInternalServiceToken() throws Exception {
        Payment payment = Payment.reconstitute(PAYMENT_ID, BOOKING_ID, "buyer@example.com", new BigDecimal("50.00"), "USD",
                PaymentStatus.SUCCEEDED, "gw-tx-1", null, Instant.now(), 1L);
        when(getPaymentUseCase.getByBookingId(BOOKING_ID)).thenReturn(Optional.of(payment));

        mockMvc.perform(get("/api/v1/payments/{bookingId}", BOOKING_ID)
                        .header("Authorization", "Bearer " + internalServiceToken()))
                .andExpect(status().isOk());

        verify(bookingOwnershipPort, never()).fetchOwnedBooking(anyString(), anyString());
    }

    // ---------------------------------------------------------------
    // Booking-ownership enforcement: retry
    // ---------------------------------------------------------------

    @Test
    void retryReturns403WhenCustomerDoesNotOwnBooking() throws Exception {
        Payment payment = Payment.reconstitute(PAYMENT_ID, BOOKING_ID, "buyer@example.com", new BigDecimal("50.00"), "USD",
                PaymentStatus.FAILED, null, "gateway error", Instant.now(), 1L);
        when(getPaymentUseCase.getById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        doThrow(new IdentityMismatchException("Caller does not own booking " + BOOKING_ID))
                .when(bookingOwnershipPort).fetchOwnedBooking(eq(BOOKING_ID), anyString());

        mockMvc.perform(post("/api/v1/payments/{paymentId}/retry", PAYMENT_ID)
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID)))
                .andExpect(status().isForbidden());

        verify(retryPaymentUseCase, never()).retry(anyString());
    }

    @Test
    void retryReturns404WhenPaymentDoesNotExist() throws Exception {
        when(getPaymentUseCase.getById("missing")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/payments/{paymentId}/retry", "missing")
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID)))
                .andExpect(status().isNotFound());

        verify(bookingOwnershipPort, never()).fetchOwnedBooking(anyString(), anyString());
    }

    @Test
    void retrySucceedsWhenCustomerOwnsBooking() throws Exception {
        Payment payment = Payment.reconstitute(PAYMENT_ID, BOOKING_ID, "buyer@example.com", new BigDecimal("50.00"), "USD",
                PaymentStatus.FAILED, null, "gateway error", Instant.now(), 1L);
        when(getPaymentUseCase.getById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(bookingOwnershipPort.fetchOwnedBooking(eq(BOOKING_ID), anyString())).thenReturn(ownedBooking());
        when(retryPaymentUseCase.retry(PAYMENT_ID)).thenReturn(Optional.of(PAYMENT_ID));

        mockMvc.perform(post("/api/v1/payments/{paymentId}/retry", PAYMENT_ID)
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID)))
                .andExpect(status().isOk());

        verify(retryPaymentUseCase).retry(PAYMENT_ID);
    }

    @Test
    void retryBypassesOwnershipCheckForInternalServiceToken() throws Exception {
        Payment payment = Payment.reconstitute(PAYMENT_ID, BOOKING_ID, "buyer@example.com", new BigDecimal("50.00"), "USD",
                PaymentStatus.FAILED, null, "gateway error", Instant.now(), 1L);
        when(getPaymentUseCase.getById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(retryPaymentUseCase.retry(PAYMENT_ID)).thenReturn(Optional.of(PAYMENT_ID));

        mockMvc.perform(post("/api/v1/payments/{paymentId}/retry", PAYMENT_ID)
                        .header("Authorization", "Bearer " + internalServiceToken()))
                .andExpect(status().isOk());

        verify(bookingOwnershipPort, never()).fetchOwnedBooking(anyString(), anyString());
    }

    // ---------------------------------------------------------------
    // Refund: internal-service token only
    // ---------------------------------------------------------------

    @Test
    void refundRejectsACustomerTokenEvenIfItOwnsTheBooking() throws Exception {
        mockMvc.perform(post("/api/v1/payments/{bookingId}/refund", BOOKING_ID)
                        .header("Authorization", "Bearer " + validToken(CUSTOMER_A_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Match cancelled"}
                                """))
                .andExpect(status().isForbidden());

        verify(refundPaymentUseCase, never()).refundByBookingId(anyString(), anyString());
    }

    @Test
    void refundSucceedsForInternalServiceToken() throws Exception {
        when(refundPaymentUseCase.refundByBookingId(BOOKING_ID, "Match cancelled"))
                .thenReturn(Optional.of(PAYMENT_ID));

        mockMvc.perform(post("/api/v1/payments/{bookingId}/refund", BOOKING_ID)
                        .header("Authorization", "Bearer " + internalServiceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Match cancelled"}
                                """))
                .andExpect(status().isOk());

        verify(refundPaymentUseCase).refundByBookingId(BOOKING_ID, "Match cancelled");
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    /**
     * The booking every ownership-passing test stubs. Its amount/currency deliberately match the
     * request bodies below — the controller now rejects a charge that disagrees with the booking,
     * so an ownership-only stub is no longer enough to reach the use case.
     */
    private static BookingOwnershipPort.OwnedBooking ownedBooking() {
        return new BookingOwnershipPort.OwnedBooking(
                BOOKING_ID, "PENDING_PAYMENT", new BigDecimal("50.00"), "USD");
    }

    // ---------------------------------------------------------------
    // Token helpers
    // ---------------------------------------------------------------

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

    private String internalServiceToken() {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("booking-service")
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                    .claim("tokenType", AuthenticatedUser.TOKEN_TYPE_INTERNAL_SERVICE)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }
}
