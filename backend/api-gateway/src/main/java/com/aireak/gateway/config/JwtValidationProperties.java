package com.aireak.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Must mirror identity-service's {@code jwt.secret} / {@code jwt.issuer} / {@code jwt.audience}
 * so the gateway can validate access tokens locally, without calling identity-service per request.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtValidationProperties(String secret, String issuer, String audience, List<String> publicPaths) {
}
