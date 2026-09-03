package com.aireak.booking.adapter.out.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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

    private MockRestServiceServer mockServer;
    private PaymentRestAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        InternalServiceTokenProvider tokenProvider = mock(InternalServiceTokenProvider.class);
        when(tokenProvider.mintServiceToken()).thenReturn("minted-internal-token");
        // paymentStatusRestClient is checkOutcome's client and is never reached from here.
        adapter = new PaymentRestAdapter(builder.build(), RestClient.create(), tokenProvider);
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
}
