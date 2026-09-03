package com.aireak.gateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Browser-facing CORS boundary. The gateway is the only origin browsers talk to directly,
 * so this is the single source of truth for allowed origins. Credentials (the refresh cookie)
 * require an explicit origin allowlist — {@code allowCredentials(true)} with a wildcard origin
 * is rejected by browsers and would be a CORS misconfiguration anyway.
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private final CorsProperties corsProperties;

    /**
     * Must run before every other gateway {@code WebFilter} (JWT auth, rate limiting, etc. —
     * see their {@code @Order(-100..-40)}). Without an explicit order this bean defaults to
     * lowest precedence and runs last, so a filter that short-circuits the chain (e.g.
     * {@code RateLimitingWebFilter} answering a preflight OPTIONS with 429) writes its response
     * before this filter ever adds CORS headers — the browser then reports a CORS failure
     * instead of the real error. Running first also means preflight requests are answered
     * directly by Spring's CORS handling without ever reaching (and consuming a token from)
     * rate limiting or auth.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Correlation-Id has to be listed on both sides: allowed so the browser's preflight lets
        // the frontend send its own ID (CorrelationIdWebFilter keeps it when it is a valid UUID v4),
        // exposed so the frontend can read the ID back off an error response and show it to the
        // user. Without the expose entry the browser hides the header from JavaScript even though
        // the gateway always sets it.
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-XSRF-TOKEN", "Idempotency-Key", "X-Correlation-Id"));
        // Retry-After belongs here for the same reason: both rate-limit filters set it beside
        // their 429 body, and the frontend's formatRateLimitMessage reads it when the body has
        // no retryAfterSeconds. It is not one of the seven CORS-safelisted response headers
        // (Cache-Control, Content-Language, Content-Length, Content-Type, Expires,
        // Last-Modified, Pragma), so without this entry the browser hides it and that fallback
        // can never be taken.
        configuration.setExposedHeaders(List.of("X-XSRF-TOKEN", "X-Correlation-Id", "Retry-After"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsWebFilter(source);
    }
}
