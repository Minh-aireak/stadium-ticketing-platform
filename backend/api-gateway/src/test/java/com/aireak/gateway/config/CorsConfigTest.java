package com.aireak.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsWebFilter;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the browser-visible half of the CORS contract. Everything the frontend reads off a
 * response by name has to be listed in {@code setExposedHeaders}: the Fetch standard's
 * safelist is only Cache-Control, Content-Language, Content-Length, Content-Type, Expires,
 * Last-Modified and Pragma, and the SPA is always cross-origin (it is served from :5173 or
 * :80 and talks to the gateway on :8080 — see frontend/Dockerfile's VITE_API_BASE_URL).
 *
 * <p>Exercises the filter directly rather than through a context: this is a pure function of
 * the CorsConfiguration the bean builds, and a {@code @SpringBootTest} would drag in
 * Redis for nothing.
 */
class CorsConfigTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private final CorsWebFilter filter =
            new CorsConfig(new CorsProperties(List.of(ALLOWED_ORIGIN))).corsWebFilter();

    private MockServerWebExchange exchangeFrom(String origin) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://gateway.local/api/v1/matches")
                        .header(HttpHeaders.ORIGIN, origin)
                        .build());
        filter.filter(exchange, ex -> Mono.empty()).block();
        return exchange;
    }

    /**
     * Retry-After is the one the 429 path depends on. Both rate-limit filters set it beside the
     * JSON body, and the frontend's formatRateLimitMessage falls back to reading it when the
     * body has no retryAfterSeconds — a fallback the browser could never take while the header
     * was unexposed.
     */
    @Test
    void exposesEveryHeaderTheFrontendReadsByName() {
        MockServerWebExchange exchange = exchangeFrom(ALLOWED_ORIGIN);

        assertThat(exchange.getResponse().getHeaders().getAccessControlExposeHeaders())
                .containsExactlyInAnyOrder("X-XSRF-TOKEN", "X-Correlation-Id", HttpHeaders.RETRY_AFTER);
    }

    @Test
    void allowsTheConfiguredOriginWithCredentials() {
        MockServerWebExchange exchange = exchangeFrom(ALLOWED_ORIGIN);

        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowCredentials()).isTrue();
    }

    @Test
    void rejectsAnOriginOutsideTheAllowlist() {
        MockServerWebExchange exchange = exchangeFrom("http://evil.example");

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
