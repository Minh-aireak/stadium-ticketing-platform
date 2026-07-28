package com.aireak.gateway.filter;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reactive WebFilter: injects X-Correlation-Id header and sets MDC for log tracing.
 *
 * <p>WebFlux equivalent of {@code common}'s CorrelationIdFilter (which is Servlet-based).
 * This one works in the reactive pipeline.
 *
 * <p>An inbound correlation id is only trusted if it is a well-formed UUID v4 (exactly 36
 * characters). Anything else — missing, blank, malformed, or longer than
 * {@link #MAX_INBOUND_LENGTH} characters (checked first so we never run the regex against an
 * arbitrarily large header value) — is replaced with a freshly generated UUID v4. The header is
 * forwarded downstream exactly once; it is never duplicated.
 *
 * <p>Ordered ahead of every other gateway filter (lowest precedence value in the chain) so the
 * correlation id is always set — including on responses short-circuited early by
 * {@code PreAuthRateLimitingWebFilter}, {@code JwtAuthenticationWebFilter}, or
 * {@code RateLimitingWebFilter} — rather than only on requests that make it all the way through.
 */
@Component
@Order(-100)
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY     = "correlationId";

    private static final int MAX_INBOUND_LENGTH = 64;

    private static final Pattern UUID_V4_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String inbound = exchange.getRequest().getHeaders().getFirst(HEADER_NAME);
        String correlationId = isValid(inbound) ? inbound : UUID.randomUUID().toString();

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER_NAME, correlationId))
                .build();
        exchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .contextWrite(ctx -> ctx.put(MDC_KEY, correlationId));
    }

    private static boolean isValid(String candidate) {
        return candidate != null
                && candidate.length() <= MAX_INBOUND_LENGTH
                && UUID_V4_PATTERN.matcher(candidate).matches();
    }
}
