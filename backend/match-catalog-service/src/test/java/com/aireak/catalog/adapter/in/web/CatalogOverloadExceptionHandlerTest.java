package com.aireak.catalog.adapter.in.web;

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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogOverloadExceptionHandlerTest {

    private final CatalogOverloadExceptionHandler handler = new CatalogOverloadExceptionHandler();

    @Test
    void bulkheadRejectionBecomes503WithRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.handleBulkheadFull(
                BulkheadFullException.createBulkheadFullException(
                        Bulkhead.of("catalog-read", BulkheadConfig.custom()
                                .maxConcurrentCalls(1)
                                .maxWaitDuration(Duration.ZERO)
                                .build())));

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
                        RateLimiter.of("catalog-read", RateLimiterConfig.custom()
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
}
