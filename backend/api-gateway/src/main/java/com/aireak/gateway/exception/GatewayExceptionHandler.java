package com.aireak.gateway.exception;

import com.aireak.gateway.filter.CorrelationIdWebFilter;
import com.aireak.gateway.util.ProblemDetailJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

/**
 * Standardizes every error this gateway can produce that never reaches the custom WebFilters'
 * own inline {@code ProblemDetail} responses (401/403/429 — see JwtAuthenticationWebFilter,
 * ActuatorAccessWebFilter, RateLimitingWebFilter) — namely errors raised by Spring Cloud
 * Gateway's own routing machinery: a downstream service refusing the connection, a route timing
 * out, an oversized body, or an unmapped route. Without this bean, those fall through to Boot's
 * {@code DefaultErrorWebExceptionHandler}, which returns a plain {@code {"error": "..."}} shape
 * instead of the {@code application/problem+json} (RFC 7807) shape every backend service and
 * this gateway's own filters already use.
 *
 * <p>{@code @Order(-2)}: runs before Boot's {@code DefaultErrorWebExceptionHandler} (registered
 * at -1 by {@code ErrorWebFluxAutoConfiguration}), the same "lower value = higher precedence"
 * ordering space every {@code WebExceptionHandler} bean is collected and tried in.
 *
 * <p><strong>Nothing from the request decides the shape of this document.</strong> The body is
 * serialized from a {@link ProblemDetail}, not assembled by string concatenation, and the two
 * fields that vary with the request are taken from sources the client does not control: the path
 * in its still-encoded form, and the correlation ID that
 * {@link CorrelationIdWebFilter#appliedCorrelationId} vouches for. Both mattered — see the two
 * comments below — and neither is guarded by the other.
 */
@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);
    private static final String TYPE_BASE = "https://aireak.com/errors/";

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status = resolveStatus(ex);
        // getRawPath(), not getPath(): getPath() DECODES, so a request to
        // /api/v1/matches/x%22,%22detail%22:%22nothing-is-wrong arrives here as a raw quote. While
        // this body was built by String.format that let any caller add fields to it; and URI.create
        // rejects the decoded form outright ("Illegal character in path"), which would turn the
        // error into a different error. The encoded form is both URI-legal and what the client
        // actually sent.
        String path = exchange.getRequest().getURI().getRawPath();

        // The ID goes in the MDC rather than into the message text: the console pattern prints
        // %X{correlationId} and the ECS file appender promotes it to a top-level field, which is
        // what makes a Kibana filter join these lines to the downstream service's.
        CorrelationIdWebFilter.withCorrelationId(exchange, () -> {
            if (status.is5xxServerError()) {
                log.error("Gateway error: path={}", path, ex);
            } else {
                log.warn("Gateway error: path={}, reason={}", path, ex.getMessage());
            }
        });

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, resolveDetail(status));
        problem.setType(URI.create(TYPE_BASE + resolveErrorType(status)));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(path));
        // Only when there is one to report. This used to read the request header directly, which on
        // this code path is the unvalidated inbound value (see appliedCorrelationId), so a client
        // could write its own JSON members into the response — and when it sent none at all, the
        // body advertised the four characters "null" as the ID to quote to support.
        String correlationId = CorrelationIdWebFilter.appliedCorrelationId(exchange);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        problem.setProperty("timestamp", Instant.now());

        DataBuffer buffer = response.bufferFactory().wrap(ProblemDetailJson.toBytes(problem));
        return response.writeWith(Mono.just(buffer));
    }

    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return HttpStatus.valueOf(rse.getStatusCode().value());
        }
        if (isCausedBy(ex, TimeoutException.class)) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        if (isCausedBy(ex, ConnectException.class) || isCausedBy(ex, IOException.class)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private boolean isCausedBy(Throwable ex, Class<? extends Throwable> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String resolveErrorType(HttpStatus status) {
        return switch (status) {
            case GATEWAY_TIMEOUT -> "gateway-timeout";
            case SERVICE_UNAVAILABLE -> "service-unavailable";
            case NOT_FOUND -> "route-not-found";
            // The RequestSize filter on the payments and bookings routes raises this, and it is by
            // some distance the most likely non-5xx to arrive here. Left to the default it was
            // reported as "internal-error" / "An unexpected error occurred", which tells a caller
            // to retry something that will fail identically every time.
            case CONTENT_TOO_LARGE -> "request-too-large";
            case BAD_REQUEST -> "bad-request";
            default -> "internal-error";
        };
    }

    private String resolveDetail(HttpStatus status) {
        return switch (status) {
            case GATEWAY_TIMEOUT -> "The downstream service took too long to respond";
            case SERVICE_UNAVAILABLE -> "The downstream service is currently unavailable";
            case NOT_FOUND -> "No route matches this request";
            case CONTENT_TOO_LARGE -> "The request body is larger than this endpoint accepts";
            default -> "An unexpected error occurred";
        };
    }
}
