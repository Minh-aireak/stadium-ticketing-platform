package com.aireak.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Restricts {@code /actuator/metrics} and {@code /actuator/gateway} (internals: route table,
 * JVM/HTTP metrics) to the {@code ADMIN} role.
 *
 * <p>This service has no Spring Security filter chain — it is a plain WebFlux app whose only
 * auth is the custom {@link JwtAuthenticationWebFilter}. Spring Boot's standard
 * {@code management.endpoint.*.roles} property only ever gates the health endpoint's
 * show-details, and even then only works when a real {@code Authentication} is present in the
 * reactive security context, which nothing here populates — so it cannot be used to protect
 * arbitrary actuator endpoints like metrics/gateway. This filter is the actual enforcement
 * point, reading the role {@link JwtAuthenticationWebFilter} already extracted from the
 * validated JWT.
 *
 * <p>Runs after {@link JwtAuthenticationWebFilter} (order -50): neither path is in
 * {@code jwt.public-paths}, so by the time this filter runs the request already carries a
 * validated token (or was already rejected with 401) and, if valid, its role attribute.
 */
@Component
@Order(-45)
public class ActuatorAccessWebFilter implements WebFilter, Ordered {

    private static final String REQUIRED_ROLE = "ADMIN";

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
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private boolean isAdminOnly(String path) {
        return isOrIsUnder(path, "/actuator/metrics") || isOrIsUnder(path, "/actuator/gateway");
    }

    private boolean isOrIsUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
