package com.aireak.booking.adapter.in.web;

import com.aireak.booking.application.port.in.CancelBookingUseCase;
import com.aireak.booking.application.port.in.CreateBookingUseCase;
import com.aireak.booking.application.port.in.GetBookingUseCase;
import com.aireak.booking.application.port.in.ListBookingsUseCase;
import com.aireak.booking.application.port.out.OutboundServiceUnavailableException;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.common.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpServerErrorException;

import java.net.SocketTimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a customer is told when the saga could not finish because something downstream was not
 * answering — as opposed to answering "no", which is already a 422.
 *
 * <p>Both routes out of {@code BookingOrchestrationService} end in {@code throw e} after
 * compensating, so whatever type the outbound adapter chose is exactly what reaches the client:
 * an unreachable ticket-inventory-service from Step 2, an unreachable payment-service from Step 4.
 */
class BookingControllerDependencyUnavailableTest {

    private static final String REQUEST_BODY = """
            {"customerId":"cust-1","showtimeId":"showtime-1","seatCodes":["A1"],
             "amount":100000,"currency":"VND"}
            """;

    private final CreateBookingUseCase createBooking = mock(CreateBookingUseCase.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new BookingController(createBooking,
                    mock(GetBookingUseCase.class), mock(ListBookingsUseCase.class),
                    mock(CancelBookingUseCase.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void authenticateAsTheBookingOwner() {
        AuthenticatedUserContext.set(
                new AuthenticatedUser("cust-1", "fan@example.com", "CUSTOMER", "token", "Bearer"));
    }

    @AfterEach
    void clearContext() {
        AuthenticatedUserContext.clear();
    }

    /**
     * Step 4: {@code PaymentRestAdapter}'s fallback raises this when the circuit is open or the
     * call timed out — no HTTP response came back at all, so nothing about it says "this service
     * is broken", which is the only thing a 500 can say.
     */
    @Test
    void anUnreachablePaymentServiceBecomes503() throws Exception {
        stubCreateBookingToThrow(new OutboundServiceUnavailableException(
                "Payment service unavailable", new SocketTimeoutException("Read timed out")));

        perform()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.type").value("https://aireak.com/errors/service-unavailable"))
                .andExpect(jsonPath("$.detail")
                        .value("A service this booking needs is temporarily unavailable"));
    }

    /**
     * A downstream that answered 503 itself — payment-service shedding load, or
     * ticket-inventory-service once it tells a catalog outage apart from a closed booking window.
     * {@code initiatePaymentFallback} rethrows an {@code HttpStatusCodeException} unchanged, so it
     * arrives at the controller as-is.
     */
    @Test
    void aDownstreamThatAnswered503Becomes503RatherThan500() throws Exception {
        stubCreateBookingToThrow(downstreamStatus(HttpStatus.SERVICE_UNAVAILABLE));

        perform()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
    }

    /**
     * The guard on the one above. A downstream 500 is not a temporary condition the caller should
     * be invited to retry, so it must keep reading as this platform having failed.
     */
    @Test
    void aDownstreamThatAnswered500StillBecomes500() throws Exception {
        stubCreateBookingToThrow(downstreamStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        perform().andExpect(status().isInternalServerError());
    }

    private void stubCreateBookingToThrow(Throwable failure) {
        when(createBooking.createBooking(any(), anyString(), anyString(), anyString(),
                any(), any(), anyString())).thenThrow(failure);
    }

    private static HttpServerErrorException downstreamStatus(HttpStatus status) {
        return HttpServerErrorException.create(
                status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], null);
    }

    private ResultActions perform() throws Exception {
        return mockMvc.perform(post("/api/v1/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY));
    }
}
