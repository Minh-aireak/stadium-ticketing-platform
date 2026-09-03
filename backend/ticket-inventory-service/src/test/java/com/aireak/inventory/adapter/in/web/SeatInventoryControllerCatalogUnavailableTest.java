package com.aireak.inventory.adapter.in.web;

import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.common.web.advice.GlobalExceptionHandler;
import com.aireak.inventory.application.port.in.ConfirmSeatsUseCase;
import com.aireak.inventory.application.port.in.GetSeatMapUseCase;
import com.aireak.inventory.application.port.in.GetSeatingLayoutUseCase;
import com.aireak.inventory.application.port.in.HoldSeatsUseCase;
import com.aireak.inventory.application.port.in.ReleaseSeatsUseCase;
import com.aireak.inventory.application.port.in.ReserveSeatsUseCase;
import com.aireak.inventory.application.port.in.UnholdSeatsUseCase;
import com.aireak.inventory.application.port.in.command.HoldSeatsCommand;
import com.aireak.inventory.domain.exception.ShowtimeBookingClosedException;
import com.aireak.inventory.domain.exception.ShowtimeCatalogUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a customer clicking a seat is told when match-catalog-service cannot be reached, versus
 * when the booking window really has closed. POST /hold is the frontend's own call — the first
 * thing SeatSelectionPage does on every click — so this status is read by a browser, not only by
 * booking-service.
 */
class SeatInventoryControllerCatalogUnavailableTest {

    private final HoldSeatsUseCase holdSeats = mock(HoldSeatsUseCase.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SeatInventoryController(
                        mock(ReserveSeatsUseCase.class),
                        mock(ReleaseSeatsUseCase.class),
                        mock(ConfirmSeatsUseCase.class),
                        mock(GetSeatMapUseCase.class),
                        mock(GetSeatingLayoutUseCase.class),
                        holdSeats,
                        mock(UnholdSeatsUseCase.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        AuthenticatedUserContext.set(
                new AuthenticatedUser("cust-1", "fan@example.com", "CUSTOMER", "token", "Bearer"));
    }

    @AfterEach
    void clearContext() {
        AuthenticatedUserContext.clear();
    }

    /**
     * The regression. Both cases used to leave as ShowtimeBookingClosedException — a
     * DomainException, which GlobalExceptionHandler answers 422 — so a catalog outage was
     * indistinguishable from a match that had genuinely stopped selling: in the status, in the
     * sentence the customer read, and in every dashboard that counts 4xx as the caller's problem
     * and 5xx as ours.
     */
    @Test
    void anUnreachableCatalogBecomes503WithRetryAfter() throws Exception {
        when(holdSeats.execute(any(HoldSeatsCommand.class)))
                .thenThrow(new ShowtimeCatalogUnavailableException("showtime-1",
                        new ResourceAccessException("I/O error",
                                new SocketTimeoutException("Read timed out"))));

        hold()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.type").value("https://aireak.com/errors/catalog-unavailable"))
                .andExpect(jsonPath("$.detail")
                        .value("Ticket availability cannot be verified right now"));
    }

    /** The guard: a booking window that really has closed must stay the 422 it always was. */
    @Test
    void aGenuinelyClosedBookingWindowStays422() throws Exception {
        when(holdSeats.execute(any(HoldSeatsCommand.class)))
                .thenThrow(new ShowtimeBookingClosedException("showtime-1"));

        hold().andExpect(status().isUnprocessableEntity());
    }

    /**
     * showtimeId is an internal identifier and this response is rendered straight into a toast by
     * the browser (errors.ts prints ProblemDetail's detail verbatim). The outage detail is a
     * constant for that reason; the closed-window one still names the showtime, because that is a
     * domain answer services correlate on.
     */
    @Test
    void theOutageDetailDoesNotNameTheShowtime() throws Exception {
        when(holdSeats.execute(any(HoldSeatsCommand.class)))
                .thenThrow(new ShowtimeCatalogUnavailableException("showtime-1",
                        new ResourceAccessException("I/O error")));

        hold().andExpect(jsonPath("$.detail").value(not(containsString("showtime-1"))));
    }

    private ResultActions hold() throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/showtime-1/hold")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatCodes\":[\"A1\"]}"));
    }
}
