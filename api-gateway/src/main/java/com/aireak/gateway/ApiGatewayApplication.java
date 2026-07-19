package com.aireak.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway — reactive WebFlux-based.
 * No DDD per architecture decision: gateway is purely technical infrastructure.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Route incoming requests to downstream services</li>
 *   <li>JWT validation filter (before routing)</li>
 *   <li>Rate limiting (future: via Redis token bucket)</li>
 *   <li>Correlation ID injection</li>
 * </ul>
 */
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
