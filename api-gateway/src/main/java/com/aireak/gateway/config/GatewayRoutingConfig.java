package com.aireak.gateway.config;

import com.aireak.gateway.filter.CorrelationIdWebFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * WebFlux routing configuration.
 * Manually routes paths to downstream services using {@link WebClient} proxy pattern.
 *
 * <p>Route map:
 * <pre>
 *   /api/v1/auth/**         → identity-service:8081
 *   /api/v1/matches/**      → match-catalog-service:8082
 *   /api/v1/inventory/**    → ticket-inventory-service:8083
 *   /api/v1/payments/**     → payment-service:8084
 *   /api/v1/notifications/** → notification-service:8085
 *   /api/v1/bookings/**     → booking-service:8086
 * </pre>
 *
 * <p>TODO: Replace with Spring Cloud Gateway when Boot 4.1.x-compatible version ships.
 */
@Configuration
public class GatewayRoutingConfig {

    @Bean
    public RouterFunction<ServerResponse> routes() {
        // Minimal health-check route for now
        // Full proxy routing will be added via ProxyController or Spring Cloud Gateway
        return RouterFunctions.route()
                .GET("/gateway/health", req -> ServerResponse.ok().bodyValue("{\"status\":\"UP\"}"))
                .build();
    }
}
