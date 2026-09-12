package com.aireak.booking.adapter.out.client;

import com.aireak.booking.application.port.out.PaymentPort.PaymentOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Pins what payment-service actually answers when another attempt already holds a booking's
 * payment idempotency guard, because {@code BookingOrchestrationService}'s Step 4 is written as
 * if it answered something else.
 *
 * <p>Step 4 carries a branch keyed on a 409/422 whose body says "already being processed" (see
 * {@code BookingOrchestrationService#isPaymentAlreadyBeingProcessed}). payment-service never
 * sends one: {@code PaymentController#initiate} catches {@code DuplicatePaymentException} and
 * answers 202 Accepted, pinned on that side by
 * {@code initiateReturnsAcceptedWhenAnIdempotentPaymentAttemptIsStillInProgress}. The two tests
 * below are the booking-side half of that pair -- the first is the path production takes, the
 * second is the path the branch is written for, and keeping both in one file is what makes the
 * difference between them visible from here.
 *
 * <p>Also pins how {@link PaymentRestAdapter#checkOutcome} reads payment-service's status
 * endpoint, and in particular that a 404 comes out as {@link PaymentOutcome#NOT_FOUND} and
 * nothing else does. {@code BookingReconciliationJob} cancels a booking on that answer; every
 * other way the lookup can fail has to stay {@link PaymentOutcome#UNKNOWN}, which nothing acts on.
 *
 * <p>Calls the adapter directly, so its {@code @Bulkhead}/{@code @CircuitBreaker}/{@code @Retry}
 * and the fallback hanging off them do nothing: what is asserted is the raw shape of the answer
 * the annotations are handed, not what they make of it. {@code TicketInventoryRestAdapter}'s
 * resilience test is the one that needs a proxy, and says so.
 */
class PaymentRestAdapterTest {

    private static final String BASE_URL = "http://payment-service";
    private static final String BOOKING_ID = "booking-1";
    private static final BigDecimal AMOUNT = new BigDecimal("175.00");
    private static final String CURRENCY = "VND";

    private static final String STATUS_URL = BASE_URL + "/api/v1/payments/" + BOOKING_ID;

    private MockRestServiceServer mockServer;
    // checkOutcome goes through its own short-timeout client (InfraConfig#paymentStatusRestClient),
    // so it gets its own mock server; the initiatePayment tests never touch this one.
    private MockRestServiceServer statusServer;
    private PaymentRestAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient.Builder statusBuilder = RestClient.builder();
        statusServer = MockRestServiceServer.bindTo(statusBuilder).build();
        InternalServiceTokenProvider tokenProvider = mock(InternalServiceTokenProvider.class);
        when(tokenProvider.mintServiceToken()).thenReturn("minted-internal-token");
        adapter = new PaymentRestAdapter(builder.build(), statusBuilder.build(), tokenProvider);
        ReflectionTestUtils.setField(adapter, "baseUrl", BASE_URL);
    }

    /**
     * The live answer. 202 is a success status, so the adapter returns normally and the saga runs
     * on to Step 5 exactly as it would after a 201 -- which is correct, because the guard is only
     * ever held by an attempt that is genuinely mid-charge (see
     * {@code PaymentIdempotencyPort#release}), but it also means booking-service cannot tell the
     * two apart and records the booking as created without a payment row of its own having been
     * committed by this call.
     */
    @Test
    void a202AcceptedIsWhatPaymentServiceSendsWhenAnotherAttemptOwnsTheGuard() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/payments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"paymentId\":null}"));

        assertThatCode(() -> adapter.initiatePayment(BOOKING_ID, AMOUNT, CURRENCY))
                .doesNotThrowAnyException();

        mockServer.verify();
    }

    /**
     * The answer Step 4's branch is written for, kept so that the branch is not merely asserted
     * against a body a unit test invented. A 4xx does leave this adapter as an
     * {@code HttpStatusCodeException} carrying the body verbatim, so the predicate would work --
     * it just has no sender today. If {@code PaymentController#initiate} ever stops catching
     * {@code DuplicatePaymentException}, this is the shape that starts arriving.
     */
    @Test
    void a422CarryingThatWordingWouldReachTheOrchestratorIntactIfAnythingEverSentOne() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/payments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"type\":\"https://aireak.com/errors/domain-error\","
                                + "\"detail\":\"Payment for booking " + BOOKING_ID
                                + " is already being processed\"}"));

        assertThatThrownBy(() -> adapter.initiatePayment(BOOKING_ID, AMOUNT, CURRENCY))
                .isInstanceOf(HttpClientErrorException.UnprocessableEntity.class)
                .satisfies(thrown -> assertThat(((HttpClientErrorException) thrown).getResponseBodyAsString())
                        .contains("already being processed"));

        mockServer.verify();
    }

    // ---- checkOutcome -------------------------------------------------------------------------

    private void statusEndpointAnswers(HttpStatus status, String body) {
        var response = withStatus(status);
        if (body != null) {
            response = response.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        statusServer.expect(requestTo(STATUS_URL)).andExpect(method(HttpMethod.GET)).andRespond(response);
    }

    private static String paymentWithStatus(String status) {
        return "{\"paymentId\":\"pay-1\",\"bookingId\":\"" + BOOKING_ID + "\",\"status\":\"" + status
                + "\",\"gatewayTransactionId\":null,\"failureReason\":null}";
    }

    @Test
    void aSucceededPaymentIsTerminal() {
        statusEndpointAnswers(HttpStatus.OK, paymentWithStatus("SUCCEEDED"));

        assertThat(adapter.checkOutcome(BOOKING_ID)).isEqualTo(PaymentOutcome.SUCCEEDED);
        statusServer.verify();
    }

    @Test
    void aFailedPaymentIsTerminal() {
        statusEndpointAnswers(HttpStatus.OK, paymentWithStatus("FAILED"));

        assertThat(adapter.checkOutcome(BOOKING_ID)).isEqualTo(PaymentOutcome.FAILED);
        statusServer.verify();
    }

    @Test
    void aPaymentStillBeingWorkedIsInFlight() {
        statusEndpointAnswers(HttpStatus.OK, paymentWithStatus("INITIATED"));

        assertThat(adapter.checkOutcome(BOOKING_ID)).isEqualTo(PaymentOutcome.IN_FLIGHT);
    }

    @Test
    void aStatusThisSideDoesNotKnowIsStillARowAndSoInFlight() {
        statusEndpointAnswers(HttpStatus.OK, paymentWithStatus("REFUNDED"));

        assertThat(adapter.checkOutcome(BOOKING_ID)).isEqualTo(PaymentOutcome.IN_FLIGHT);
    }

    /**
     * The answer that used to disappear into "unknown". payment-service only says 404 when it
     * has no row for the booking, and it writes that row before it ever talks to the gateway --
     * so this is a statement about the payment, not about the lookup.
     */
    @Test
    void a404IsPaymentServiceSayingThereIsNoPayment() {
        statusEndpointAnswers(HttpStatus.NOT_FOUND, null);

        assertThat(adapter.checkOutcome(BOOKING_ID)).isEqualTo(PaymentOutcome.NOT_FOUND);
        statusServer.verify();
    }

    /**
     * A refused token is this side's problem, not evidence about the payment: it must never read
     * as NOT_FOUND, or a misconfigured internal-service secret would have the reconciliation job
     * cancelling every PENDING_PAYMENT booking on the platform.
     */
    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"UNAUTHORIZED", "FORBIDDEN"})
    void aRefusedTokenIsNotAMissingPayment(HttpStatus refused) {
        statusEndpointAnswers(refused, null);

        assertThat(adapter.checkOutcome(BOOKING_ID)).isEqualTo(PaymentOutcome.UNKNOWN);
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"INTERNAL_SERVER_ERROR", "BAD_GATEWAY", "SERVICE_UNAVAILABLE"})
    void aServerSideFailureIsUnknown(HttpStatus failure) {
        statusEndpointAnswers(failure, null);

        assertThat(adapter.checkOutcome(BOOKING_ID)).isEqualTo(PaymentOutcome.UNKNOWN);
    }

    @Test
    void anUnreachablePaymentServiceIsUnknown() {
        statusServer.expect(requestTo(STATUS_URL)).andRespond(withException(new IOException("Connection refused")));

        assertThat(adapter.checkOutcome(BOOKING_ID)).isEqualTo(PaymentOutcome.UNKNOWN);
    }

    @Test
    void anEmptyBodyIsUnknown() {
        statusEndpointAnswers(HttpStatus.OK, null);

        assertThat(adapter.checkOutcome(BOOKING_ID)).isEqualTo(PaymentOutcome.UNKNOWN);
    }
}
