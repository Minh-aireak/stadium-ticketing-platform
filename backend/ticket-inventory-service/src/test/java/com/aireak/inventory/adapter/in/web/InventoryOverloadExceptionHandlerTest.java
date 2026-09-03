package com.aireak.inventory.adapter.in.web;

import com.aireak.common.web.advice.GlobalExceptionHandler;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryOverloadExceptionHandlerTest {

    private final InventoryOverloadExceptionHandler handler = new InventoryOverloadExceptionHandler();

    @Test
    void bulkheadRejectionBecomes503WithRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleBulkheadFull(bulkheadFull());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getType())
                .hasToString("https://aireak.com/errors/bulkhead-full");
    }

    @Test
    void rateLimiterRejectionBecomes503WithRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleRateLimited(
                RequestNotPermitted.createRequestNotPermitted(
                        RateLimiter.of("seat-inventory", RateLimiterConfig.custom()
                                .limitForPeriod(1)
                                .limitRefreshPeriod(Duration.ofSeconds(1))
                                .timeoutDuration(Duration.ZERO)
                                .build())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getType())
                .hasToString("https://aireak.com/errors/rate-limited");
    }

    /**
     * The two tests above call the handler method directly, which proves what it returns but not
     * that Spring ever calls it. Spring takes the FIRST {@code @ControllerAdvice} in order that has
     * a handler for the thrown type, and {@code GlobalExceptionHandler} has one for
     * {@link Exception} — so if it is asked first, a bulkhead rejection comes back as its generic
     * 500 and this class is never consulted. Registering it first here is the adverse order on
     * purpose: only {@code @Order} makes the assertion below hold.
     */
    @Test
    void bulkheadRejectionStillBecomes503WhenGlobalExceptionHandlerIsRegisteredFirst() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new OverloadedController())
                .setControllerAdvice(new GlobalExceptionHandler(), handler)
                .build();

        mockMvc.perform(get("/overloaded"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
    }

    private static BulkheadFullException bulkheadFull() {
        return BulkheadFullException.createBulkheadFullException(
                Bulkhead.of("seat-inventory", BulkheadConfig.custom()
                        .maxConcurrentCalls(1)
                        .maxWaitDuration(Duration.ZERO)
                        .build()));
    }

    @RestController
    static class OverloadedController {
        @GetMapping("/overloaded")
        String reserve() {
            throw bulkheadFull();
        }
    }
}
