package com.aireak.common.web.advice;

import com.aireak.common.exception.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Global exception handler following RFC 7807 Problem Details for HTTP APIs.
 * All responses use {@link ProblemDetail} (Spring 6+ native support).
 *
 * <p>Layering rule: this class lives in {@code adapter/in/web} concern
 * within common — it translates domain exceptions to HTTP problem details
 * without leaking stack traces to clients.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TYPE_BASE = "https://aireak.com/errors/";

    /**
     * Handles all DomainException subclasses → 422 Unprocessable Entity.
     * Domain violations are client-correctable errors.
     */
    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomainException(DomainException ex) {
        log.warn("Domain exception: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "domain-error"));
        problem.setTitle("Domain Rule Violation");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles Bean Validation failures → 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        String fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.debug("Validation failed: {}", fieldErrors);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, fieldErrors);
        problem.setType(URI.create(TYPE_BASE + "validation-error"));
        problem.setTitle("Validation Failed");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Catch-all for unexpected exceptions → 500.
     * Logs full stack trace server-side; returns sanitized message to client.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setType(URI.create(TYPE_BASE + "internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
