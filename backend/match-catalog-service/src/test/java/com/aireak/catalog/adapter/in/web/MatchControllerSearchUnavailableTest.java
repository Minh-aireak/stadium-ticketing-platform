package com.aireak.catalog.adapter.in.web;

import com.aireak.catalog.application.port.in.AddShowtimeUseCase;
import com.aireak.catalog.application.port.in.CancelMatchUseCase;
import com.aireak.catalog.application.port.in.CompleteMatchUseCase;
import com.aireak.catalog.application.port.in.CreateMatchUseCase;
import com.aireak.catalog.application.port.in.GetMatchUseCase;
import com.aireak.catalog.application.port.in.ListMatchesUseCase;
import com.aireak.catalog.application.port.in.PublishMatchUseCase;
import com.aireak.catalog.application.port.out.MatchSearchException;
import com.aireak.common.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/matches?q= is the only path in this service that touches Elasticsearch. The browse
 * path right beside it reads Postgres and is unaffected by the cluster being down, so a customer
 * who types into the search box is the only one who sees this.
 */
class MatchControllerSearchUnavailableTest {

    private MockMvc mockMvcWithSearchFailing() {
        ListMatchesUseCase listMatches = mock(ListMatchesUseCase.class);
        when(listMatches.listMatches(anyString(), anyInt(), anyInt()))
                .thenThrow(new MatchSearchException(
                        "Failed to search matches with query: chelsea",
                        new IOException("Connection refused: elasticsearch/172.18.0.5:9200")));

        return MockMvcBuilders
                .standaloneSetup(new MatchController(
                        mock(CreateMatchUseCase.class),
                        mock(AddShowtimeUseCase.class),
                        mock(PublishMatchUseCase.class),
                        listMatches,
                        mock(GetMatchUseCase.class),
                        mock(CancelMatchUseCase.class),
                        mock(CompleteMatchUseCase.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * An unreachable Elasticsearch cluster is a dependency that is temporarily down, not a defect
     * in this service. Answering 500 tells the caller the opposite: that retrying is pointless and
     * someone has to go and read a stack trace.
     */
    @Test
    void anUnreachableSearchClusterBecomes503WithRetryAfter() throws Exception {
        mockMvcWithSearchFailing().perform(get("/api/v1/matches").param("q", "chelsea"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.type").value("https://aireak.com/errors/search-unavailable"));
    }

    /**
     * The detail is a constant, like GlobalExceptionHandler's for a malformed body or a constraint
     * violation: MatchSearchException's own message embeds the search query, and its cause embeds
     * the cluster's host and port.
     */
    @Test
    void theClusterAddressAndTheQueryStayOutOfTheResponse() throws Exception {
        mockMvcWithSearchFailing().perform(get("/api/v1/matches").param("q", "chelsea"))
                .andExpect(jsonPath("$.detail").value("Match search is temporarily unavailable"));
    }
}
