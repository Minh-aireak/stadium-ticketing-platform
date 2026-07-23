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

    // This filter (order -50) still returns without ever calling chain.filter() on rejection —
    // RateLimitingWebFilter (order -40), the per-route/per-user policy filter, correctly never
    // runs for a rejected request. That's fine now: CorrelationIdWebFilter (order -100) and
    // PreAuthRateLimitingWebFilter (order -60) both run BEFORE this filter, not after, so every
    // request — including one this filter rejects with 401 — already has its correlation id set
    // and has already passed the coarse per-IP flood guard by the time it gets here. See
    // PreAuthRateLimitingWebFilterTest for proof that repeated 401-bound traffic actually gets
    // capped.
    @Test
    void rejectionNeverInvokesTheRestOfTheChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123").build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainInvoked[0]).isFalse();
    }

    @Test
    void rejectsMalformedToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt-at-all")
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokenSignedWithAWrongSecret() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "a-completely-different-secret-key-that-is-long-enough".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(wrongKey)
                .compact();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokenWithWrongIssuer() {
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer("some-other-issuer")
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokenWithWrongAudience() {
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add("some-other-audience").and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsNonBearerAuthorizationScheme() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // identity-service's JwtTokenGeneratorAdapter always sets an "email" claim today, so this is
    // dormant in practice — but if a future token type (e.g. a service-to-service token) is ever
    // issued without that claim, X-User-Email must be absent rather than the literal string
    // "null", which a downstream service (e.g. notification-service) could mistake for a real value.
    @Test
    void leavesXUserEmailAbsentWhenEmailClaimIsMissing() {
        String accountId = UUID.randomUUID().toString();
        String tokenWithoutEmail = Jwts.builder()
                .subject(accountId)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(secretKey)
                .compact();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithoutEmail)
                        .build());

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Email")).isNull();
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
