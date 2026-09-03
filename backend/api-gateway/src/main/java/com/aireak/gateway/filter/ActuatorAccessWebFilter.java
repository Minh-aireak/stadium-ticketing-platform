package com.aireak.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import com.aireak.gateway.util.ProblemDetailJson;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;

/**
 * Restricts all non-public {@code /actuator/**} endpoints (e.g. metrics, gateway, env, beans) to
 * the {@code ADMIN} role. {@code /actuator/prometheus} is public alongside health/info — Prometheus
 * has no user JWT to present when it scrapes (see {@code docker-compose.yaml}'s prometheus service).
 */
@Component
@Order(-45)
public class ActuatorAccessWebFilter implements WebFilter, Ordered {

    private static final String REQUIRED_ROLE = "ADMIN";
    private static final String TYPE_BASE = "https://aireak.com/errors/";

    @Override
    public int getOrder() {
        return -45;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!isAdminOnly(path)) {
            return chain.filter(exchange);
        }

        String role = exchange.getAttribute(JwtAuthenticationWebFilter.USER_ROLE_ATTRIBUTE);
        if (!REQUIRED_ROLE.equals(role)) {
            return forbidden(exchange, "ADMIN role required for actuator access");
        }
        return chain.filter(exchange);
    }

    private boolean isAdminOnly(String path) {
        if (!path.startsWith("/actuator")) {
            return false;
        }
        return !path.equals("/actuator") && !path.equals("/actuator/")
                && !path.startsWith("/actuator/health")
                && !path.startsWith("/actuator/info")
                && !path.startsWith("/actuator/prometheus");
    }

    /**
     * Builds the 403 body with {@link ProblemDetailJson}, not string concatenation, for the reason
     * {@code JwtAuthenticationWebFilter} already documents on its own 401: {@code instance}
     * carries the request path, and a path can contain a quote. Taken here in its still-encoded
     * form ({@code getRawPath()}), which is both what the client sent and the only form
     * {@link URI#create} accepts — the decoded one is rejected as an illegal character, turning
     * the 403 into a 500.
     */
    private Mono<Void> forbidden(ServerWebExchange exchange, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        problem.setType(URI.create(TYPE_BASE + "forbidden"));
        problem.setTitle("Forbidden");
        problem.setInstance(URI.create(exchange.getRequest().getURI().getRawPath()));
        problem.setProperty("timestamp", Instant.now());

        DataBuffer buffer = response.bufferFactory().wrap(ProblemDetailJson.toBytes(problem));
        return response.writeWith(Mono.just(buffer));
    }
}
