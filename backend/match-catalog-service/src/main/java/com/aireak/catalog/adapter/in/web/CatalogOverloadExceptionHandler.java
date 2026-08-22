package com.aireak.catalog.adapter.in.web;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * Maps Resilience4j's Bulkhead/RateLimiter rejections on the public browse/search use cases to
 * 503 Service Unavailable with a Retry-After header, instead of falling through to
 * {@code GlobalExceptionHandler}'s generic 500 — these are deliberate fail-fast rejections
 * under load, not unexpected server errors.
 */
@Slf4j
@RestControllerAdvice
public class CatalogOverloadExceptionHandler {

    private static final String TYPE_BASE = "https://aireak.com/errors/";
    private static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ProblemDetail> handleBulkheadFull(BulkheadFullException ex) {
        log.warn("Bulkhead full, rejecting request: {}", ex.getMessage());
        return overloadResponse("bulkhead-full", "Match catalog service is at capacity");
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RequestNotPermitted ex) {
        log.warn("Rate limit exceeded, rejecting request: {}", ex.getMessage());
        return overloadResponse("rate-limited", "Too many catalog browse requests");
    }

    private ResponseEntity<ProblemDetail> overloadResponse(String typeSuffix, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        problem.setType(URI.create(TYPE_BASE + typeSuffix));
        problem.setTitle("Service Overloaded");
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }
}
