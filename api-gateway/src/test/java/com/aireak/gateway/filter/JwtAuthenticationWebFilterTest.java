package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationWebFilterTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    private final JwtValidationProperties properties = new JwtValidationProperties(
            SECRET, ISSUER, AUDIENCE,
            List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                    "/api/v1/auth/logout", "/actuator/health", "/actuator/info"));

    private final JwtAuthenticationWebFilter filter = new JwtAuthenticationWebFilter(properties);
    private final SecretKey secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Test
    void rejectsProtectedPathWithoutBearerToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123").build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsExpiredToken() {
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(secretKey)
                .compact();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void setsTrustedUserHeadersFromValidTokenAndForwards() {
        String accountId = UUID.randomUUID().toString();
        String token = validToken(accountId, "user@example.com");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        HttpHeaders forwardedHeaders = forwarded.get().getRequest().getHeaders();
        assertThat(forwardedHeaders.getFirst("X-User-Id")).isEqualTo(accountId);
        assertThat(forwardedHeaders.getFirst("X-User-Email")).isEqualTo("user@example.com");
    }

    @Test
    void stripsClientForgedIdentityHeadersOnPublicPaths() {
        // /api/v1/auth/logout is public — never validated — but a client could still attach
        // these headers itself. They must never reach downstream services or gateway-side
        // logic (e.g. rate limiting) that trusts them as gateway-verified.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/logout")
                        .header("X-User-Id", "victim-account-id")
                        .header("X-User-Email", "victim@example.com")
                        .build());

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        HttpHeaders forwardedHeaders = forwarded.get().getRequest().getHeaders();
        assertThat(forwardedHeaders.getFirst("X-User-Id")).isNull();
        assertThat(forwardedHeaders.getFirst("X-User-Email")).isNull();
    }

    private String validToken(String subject, String email) {
        return Jwts.builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("email", email)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
    }
}
