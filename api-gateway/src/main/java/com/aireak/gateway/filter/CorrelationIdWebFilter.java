package com.aireak.gateway.filter;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive WebFilter: injects X-Correlation-ID header and sets MDC for log tracing.
 *
 * <p>WebFlux equivalent of {@code common}'s CorrelationIdFilter (which is Servlet-based).
 * This one works in the reactive pipeline.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY     = "correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(HEADER_NAME);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String finalId = correlationId;
        exchange.getResponse().getHeaders().add(HEADER_NAME, finalId);

        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(MDC_KEY, finalId));
    }
}
