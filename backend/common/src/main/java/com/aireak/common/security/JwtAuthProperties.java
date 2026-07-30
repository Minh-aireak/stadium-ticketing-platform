package com.aireak.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration properties for JWT authentication across platform microservices.
 * Supports primary {@code secret}, optional {@code previousSecret} for zero-downtime secret rotation,
 * and distinct {@code internalSecret} for service-to-service communication tokens.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtAuthProperties(
        String secret,
        String previousSecret,
        String internalSecret,
        String issuer,
        String audience,
        List<String> excludedPaths
) {

    public JwtAuthProperties {
        if (excludedPaths == null) {
            excludedPaths = List.of();
        }
    }
}
