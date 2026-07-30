package com.aireak.common.web.advice;

import com.aireak.common.exception.DomainException;
import com.aireak.common.exception.ForbiddenException;
import com.aireak.common.exception.IdentityMismatchException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * Handles a customerId/userId in the request that doesn't match the JWT-authenticated
     * caller → 403 Forbidden. Distinct from {@link DomainException} (422): this is an
     * authorization failure, not a correctable input error.
     */
    @ExceptionHandler(IdentityMismatchException.class)
    public ProblemDetail handleIdentityMismatch(IdentityMismatchException ex) {
        log.warn("Identity mismatch: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "identity-mismatch"));
        problem.setTitle("Identity Mismatch");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles a caller whose role doesn't satisfy an endpoint's authorization requirement →
     * 403 Forbidden. Distinct from {@link IdentityMismatchException}: this is a role/permission
     * failure, not an identity/ownership mismatch.
     */
    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex) {
        log.warn("Forbidden: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "forbidden"));
        problem.setTitle("Forbidden");
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
     * Handles a missing required {@code @RequestParam} → 400 Bad Request.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        log.debug("Missing request parameter: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "validation-error"));
        problem.setTitle("Validation Failed");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles Bean Validation failures on method parameters (e.g. {@code @RequestParam}/
     * {@code @PathVariable} constraints validated outside a {@code @Valid} request body) → 400.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        String violations = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.debug("Constraint violation: {}", violations);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, violations);
        problem.setType(URI.create(TYPE_BASE + "validation-error"));
        problem.setTitle("Validation Failed");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles Bean Validation failures on {@code @Validated} controller method parameters
     * (Spring's own validation exception, distinct from {@link ConstraintViolationException})
     * → 400 Bad Request.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        log.debug("Handler method validation failed: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "validation-error"));
        problem.setTitle("Validation Failed");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles a request body that can't be parsed (malformed JSON, wrong type, etc.) → 400.
     * The client-facing message is a generic constant — {@link HttpMessageNotReadableException}'s
     * own message can embed internal type/package names, which must not leak to the client.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.debug("Malformed request body: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed JSON request body");
        problem.setType(URI.create(TYPE_BASE + "validation-error"));
        problem.setTitle("Validation Failed");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles a persistence-layer constraint violation (unique/FK/check constraint) → 409
     * Conflict. The client-facing message is a generic constant — the raw exception can embed
     * constraint names or SQL fragments, which must not leak to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request conflicts with existing data");
        problem.setType(URI.create(TYPE_BASE + "data-conflict"));
        problem.setTitle("Data Conflict");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles a path variable / request parameter that can't be converted to its declared type
     * (e.g. {@code ?page=abc} on an {@code int page}) → 400 Bad Request.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.debug("Method argument type mismatch: {}", ex.getMessage());
        String detail = "Parameter '" + ex.getName() + "' has an invalid value";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create(TYPE_BASE + "validation-error"));
        problem.setTitle("Validation Failed");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles a request made with an HTTP method the matched route doesn't support (e.g. DELETE
     * on a GET-only endpoint) → 405 Method Not Allowed.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.debug("Method not supported: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "method-not-allowed"));
        problem.setTitle("Method Not Allowed");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles a request whose {@code Content-Type} the matched route doesn't accept (e.g. XML
     * body posted to a JSON-only endpoint) → 415 Unsupported Media Type.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.debug("Media type not supported: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problem.setType(URI.create(TYPE_BASE + "unsupported-media-type"));
        problem.setTitle("Unsupported Media Type");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles a request path with no matching route → 404 Not Found. Two distinct Spring
     * exceptions cover this depending on servlet configuration: {@link NoResourceFoundException}
     * (Boot 3.2+'s default for an unmatched path under DispatcherServlet) and
     * {@link NoHandlerFoundException} (only thrown when static resource handling is disabled).
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail handleNoRouteFound(Exception ex) {
        log.debug("No route found: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "No resource found for this request");
        problem.setType(URI.create(TYPE_BASE + "not-found"));
        problem.setTitle("Not Found");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Handles an illegal argument raised directly by application/domain code (not already wrapped
     * in a {@link DomainException} subclass) → 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
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
