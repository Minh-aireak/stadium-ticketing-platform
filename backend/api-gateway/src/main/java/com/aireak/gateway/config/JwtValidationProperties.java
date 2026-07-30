package com.aireak.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Must mirror identity-service's {@code jwt.secret} / {@code jwt.previous-secret} / {@code jwt.issuer} / {@code jwt.audience}
 * so the gateway can validate access tokens locally, supporting zero-downtime secret rotation.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtValidationProperties(String secret, String previousSecret, String issuer, String audience, List<String> publicPaths) {
}
