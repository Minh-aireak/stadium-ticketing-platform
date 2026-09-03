package com.aireak.inventory.adapter.in.web;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * Maps Resilience4j's Bulkhead/RateLimiter rejections on the reserve/release use cases to
 * 503 Service Unavailable with a Retry-After header, instead of falling through to
 * {@code GlobalExceptionHandler}'s generic 500 — these are deliberate fail-fast rejections
 * under load, not unexpected server errors.
 *
 * <p>{@code @Order} is what actually makes that happen, and is not decoration. Spring asks each
 * {@code @ControllerAdvice} in order and takes the first one that has a handler for the exception;
 * {@code GlobalExceptionHandler} has {@code @ExceptionHandler(Exception.class)}, so it has a
 * handler for every exception there is. With both advices unordered they both sat at
 * {@code LOWEST_PRECEDENCE} and the winner was decided by bean-definition order — which comes from
 * the order the two packages happen to be listed in {@code @SpringBootApplication(scanBasePackages)}.
 * Listing {@code com.aireak.common} first there, an edit with no visible connection to this class,
 * turned every load-shed rejection into a 500 logged as "Unexpected error".
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class InventoryOverloadExceptionHandler {

    private static final String TYPE_BASE = "https://aireak.com/errors/";
    private static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ProblemDetail> handleBulkheadFull(BulkheadFullException ex) {
        log.warn("Bulkhead full, rejecting request: {}", ex.getMessage());
        return overloadResponse("bulkhead-full", "Seat inventory service is at capacity");
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RequestNotPermitted ex) {
        log.warn("Rate limit exceeded, rejecting request: {}", ex.getMessage());
        return overloadResponse("rate-limited", "Too many concurrent reservation attempts");
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
