package com.aireak.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Restricts all non-public {@code /actuator/**} endpoints (e.g. metrics, gateway, env, beans) to the {@code ADMIN} role.
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
                && !path.startsWith("/actuator/info");
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String body = String.format(
                "{\"type\":\"%sforbidden\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"%s\",\"instance\":\"%s\",\"timestamp\":\"%s\"}",
                TYPE_BASE, detail, exchange.getRequest().getURI().getPath(), Instant.now()
        );
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
