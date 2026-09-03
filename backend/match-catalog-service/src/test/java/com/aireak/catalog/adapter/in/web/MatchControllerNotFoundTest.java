package com.aireak.catalog.adapter.in.web;

import com.aireak.catalog.application.port.in.AddShowtimeUseCase;
import com.aireak.catalog.application.port.in.CancelMatchUseCase;
import com.aireak.catalog.application.port.in.CompleteMatchUseCase;
import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.GetMatchUseCase;
import com.aireak.catalog.application.port.in.ListMatchesUseCase;
import com.aireak.catalog.application.port.in.PublishMatchUseCase;
import com.aireak.catalog.domain.exception.InvalidMatchStatusException;
import com.aireak.catalog.domain.exception.MatchNotFoundException;
import com.aireak.common.security.AuthenticatedUser;
import com.aireak.common.security.AuthenticatedUserContext;
import com.aireak.common.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The same missing match, asked about two ways. GET already answered 404 by mapping an empty
 * Optional; every write path threw MatchNotFoundException, which was a DomainException and so
 * collected GlobalExceptionHandler's blanket 422 — one service giving two different answers to
 * "this match does not exist".
 */
class MatchControllerNotFoundTest {

    private static final String MISSING = "match-does-not-exist";

    private final PublishMatchUseCase publishMatch = mock(PublishMatchUseCase.class);
    private final CompleteMatchUseCase completeMatch = mock(CompleteMatchUseCase.class);
    private final GetMatchUseCase getMatch = mock(GetMatchUseCase.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new MatchController(
                    mock(CreateMatchUseCase.class),
                    mock(AddShowtimeUseCase.class),
                    publishMatch,
                    mock(ListMatchesUseCase.class),
                    getMatch,
                    mock(CancelMatchUseCase.class),
                    completeMatch))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void authenticateAsAdmin() {
        AuthenticatedUserContext.set(
                new AuthenticatedUser("admin-1", "admin@example.com", "ADMIN", "token", "Bearer"));
    }

    @AfterEach
    void clearContext() {
        AuthenticatedUserContext.clear();
    }

    /** What the read side has always answered, pinned here as the reference the writes must match. */
    @Test
    void gettingAMatchThatDoesNotExistIs404() throws Exception {
        when(getMatch.getMatch(MISSING)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/matches/{id}", MISSING)).andExpect(status().isNotFound());
    }

    @Test
    void publishingAMatchThatDoesNotExistIs404() throws Exception {
        doThrow(new MatchNotFoundException(MISSING)).when(publishMatch).publishMatch(MISSING);

        mockMvc.perform(put("/api/v1/matches/{id}/publish", MISSING)).andExpect(status().isNotFound());
    }

    @Test
    void completingAMatchThatDoesNotExistIs404() throws Exception {
        doThrow(new MatchNotFoundException(MISSING)).when(completeMatch).completeMatch(MISSING);

        mockMvc.perform(put("/api/v1/matches/{id}/complete", MISSING)).andExpect(status().isNotFound());
    }

    /**
     * The guard. Only "no such match" moves; a match that exists and is in the wrong state to be
     * published is still a domain rule violation about a resource that is really there, and has to
     * stay 422.
     */
    @Test
    void publishingAMatchInTheWrongStateStays422() throws Exception {
        doThrow(new InvalidMatchStatusException("Match is already PUBLISHED"))
                .when(publishMatch).publishMatch(MISSING);

        mockMvc.perform(put("/api/v1/matches/{id}/publish", MISSING))
                .andExpect(status().isUnprocessableEntity());
    }
}
