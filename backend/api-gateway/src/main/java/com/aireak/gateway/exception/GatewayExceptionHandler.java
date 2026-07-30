package com.aireak.gateway.exception;

import com.aireak.gateway.filter.CorrelationIdWebFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

/**
 * Standardizes every error this gateway can produce that never reaches the custom WebFilters'
 * own inline {@code ProblemDetail} responses (401/403/429 — see JwtAuthenticationWebFilter,
 * ActuatorAccessWebFilter, RateLimitingWebFilter) — namely errors raised by Spring Cloud
 * Gateway's own routing machinery: a downstream service refusing the connection, a route timing
 * out, or an unmapped route. Without this bean, those fall through to Boot's
 * {@code DefaultErrorWebExceptionHandler}, which returns a plain {@code {"error": "..."}} shape
 * instead of the {@code application/problem+json} (RFC 7807) shape every backend service and
 * this gateway's own filters already use.
 *
 * <p>{@code @Order(-2)}: runs before Boot's {@code DefaultErrorWebExceptionHandler} (registered
 * at -1 by {@code ErrorWebFluxAutoConfiguration}), the same "lower value = higher precedence"
 * ordering space every {@code WebExceptionHandler} bean is collected and tried in.
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
        String errorType = resolveErrorType(status);
        String detail = resolveDetail(status);
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdWebFilter.HEADER_NAME);

        if (status.is5xxServerError()) {
            log.error("Gateway error: path={}, correlationId={}", exchange.getRequest().getURI().getPath(),
                    correlationId, ex);
        } else {
            log.warn("Gateway error: path={}, correlationId={}, reason={}",
                    exchange.getRequest().getURI().getPath(), correlationId, ex.getMessage());
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String body = String.format(
                "{\"type\":\"%s%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"," +
                        "\"correlationId\":\"%s\",\"timestamp\":\"%s\"}",
                TYPE_BASE, errorType, status.getReasonPhrase(), status.value(), detail,
                exchange.getRequest().getURI().getPath(), correlationId, Instant.now());
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
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
            case BAD_REQUEST -> "bad-request";
            default -> "internal-error";
        };
    }

    private String resolveDetail(HttpStatus status) {
        return switch (status) {
            case GATEWAY_TIMEOUT -> "The downstream service took too long to respond";
            case SERVICE_UNAVAILABLE -> "The downstream service is currently unavailable";
            case NOT_FOUND -> "No route matches this request";
            default -> "An unexpected error occurred";
        };
    }
}
