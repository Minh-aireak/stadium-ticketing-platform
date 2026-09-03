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

    /**
     * Runs {@code logging} with this exchange's correlation ID in the MDC, so a gateway log line
     * carries the same {@code correlationId} field every downstream service logs — a field Kibana
     * can filter on, rather than an ID spelled out inside the message text that it cannot.
     *
     * <p>Needed because {@link #filter} can only put the ID in the Reactor context: the gateway is
     * reactive, and a log statement inside a {@code flatMap} (both rate-limit filters log from
     * Redis's callback thread) runs on a thread that never saw this filter's MDC. Bridging the
     * Reactor context to the MDC automatically would need {@code io.micrometer:context-propagation}
     * plus a global {@code Hooks.enableAutomaticContextPropagation()}; with only a handful of log
     * sites in this service, each one setting and clearing the MDC around its own synchronous call
     * costs nothing at runtime and adds no dependency.
     */
    public static void withCorrelationId(ServerWebExchange exchange, Runnable logging) {
        String correlationId = appliedCorrelationId(exchange);
        if (correlationId == null) {
            // Only reachable when the failure happened before this filter ran: @Order(-100) puts it
            // ahead of every other gateway filter, but not ahead of an error thrown while the
            // request itself is being decoded. Log the line untagged rather than dropping it.
            logging.run();
            return;
        }
        MDC.put(MDC_KEY, correlationId);
        try {
            logging.run();
        } finally {
            // Netty event-loop and Redisson callback threads are shared across requests, so a
            // leaked entry would stamp the next request's log lines with this request's ID.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * The correlation ID this exchange was actually processed and logged under, or {@code null}
     * when {@link #filter} never ran for it.
     *
     * <p>Reads the RESPONSE header this filter set, not the request header, and the difference is
     * not cosmetic. {@link #filter} sanitises by handing the chain a mutated <em>copy</em> of the
     * exchange; the original still carries the client's raw header. Everything inside the chain
     * sees the copy, but a {@code WebExceptionHandler} does not run inside the chain —
     * {@code ExceptionHandlingWebHandler} sits above {@code FilteringWebHandler} and hands the
     * handler the exchange it received itself. So on the error path the request header is
     * whatever arrived from the network, and that value would otherwise reach both the MDC (and
     * from there a top-level field of the ECS log file) and the error body. The response header is
     * set on the shared response object before the mutation, so it is the sanitised value for
     * both copies.
     *
     * <p>Falls back to the request header only after re-checking it, which covers the one case
     * where no response header exists yet: a failure raised before this filter ran at all.
     */
    public static String appliedCorrelationId(ServerWebExchange exchange) {
        String applied = exchange.getResponse().getHeaders().getFirst(HEADER_NAME);
        if (applied != null) {
            return applied;
        }
        String inbound = exchange.getRequest().getHeaders().getFirst(HEADER_NAME);
        return isValid(inbound) ? inbound : null;
    }

    private static boolean isValid(String candidate) {
        return candidate != null
                && candidate.length() <= MAX_INBOUND_LENGTH
                && UUID_V4_PATTERN.matcher(candidate).matches();
    }
}
