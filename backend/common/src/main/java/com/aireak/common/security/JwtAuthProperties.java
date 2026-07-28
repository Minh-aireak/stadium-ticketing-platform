package com.aireak.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Must mirror identity-service's {@code jwt.secret} / {@code jwt.issuer} / {@code jwt.audience}
 * (and api-gateway's {@code JwtValidationProperties}) so every downstream service can validate
 * access tokens with the same HMAC key, independently of the gateway.
 *
 * <p>{@code excludedPaths} (Ant-style patterns) are skipped entirely by
 * {@link com.aireak.common.web.filter.JwtAuthenticationFilter} — typically just
 * {@code /actuator/**} for health/liveness checks. Defaults to an empty list (secure by
 * default: a service that forgets to configure it protects everything, including actuator).
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtAuthProperties(String secret, String issuer, String audience, List<String> excludedPaths) {

    public JwtAuthProperties {
        if (excludedPaths == null) {
            excludedPaths = List.of();
        }
    }
}
