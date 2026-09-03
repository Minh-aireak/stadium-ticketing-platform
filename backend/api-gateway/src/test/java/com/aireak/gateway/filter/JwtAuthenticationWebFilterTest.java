package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JwtAuthenticationWebFilterTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    private final JwtValidationProperties properties = new JwtValidationProperties(
            SECRET, null, ISSUER, AUDIENCE,
            List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                    "/api/v1/auth/logout", "/actuator/health", "/actuator/info"));

    private final JwtAuthenticationWebFilter filter = new JwtAuthenticationWebFilter(properties);

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
    // and has already passed the coarse per-IP flood guard by the time it gets here.
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
        String wrongSecret = "a-completely-different-secret-key-that-is-long-enough";
        String token = buildToken(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), wrongSecret);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokenWithWrongIssuer() {
        String token = buildToken(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer("some-other-issuer")
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsTokenWithWrongAudience() {
        String token = buildToken(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(List.of("some-other-audience"))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
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
        String tokenWithoutEmail = buildToken(new JWTClaimsSet.Builder()
                .subject(accountId)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
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
        String expired = buildToken(new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now().minusSeconds(120)))
                .expirationTime(Date.from(Instant.now().minusSeconds(60)))
                .build(), SECRET);
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
    void exposesRoleClaimAsExchangeAttributeForDownstreamFilters() {
        String accountId = UUID.randomUUID().toString();
        String token = buildToken(new JWTClaimsSet.Builder()
                .subject(accountId)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .claim("role", "ADMIN")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(forwarded.get().<String>getAttribute(JwtAuthenticationWebFilter.USER_ROLE_ATTRIBUTE))
                .isEqualTo("ADMIN");
    }

    @Test
    void leavesRoleAttributeAbsentWhenRoleClaimIsMissing() {
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

        assertThat(forwarded.get().<String>getAttribute(JwtAuthenticationWebFilter.USER_ROLE_ATTRIBUTE)).isNull();
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

    @Test
    void acceptsTokenSignedWithPreviousSecretDuringRotation() {
        String previousSecret = "old-secret-key-at-least-32-bytes-long-for-hs256!!";
        JwtValidationProperties rotationProperties = new JwtValidationProperties(
                SECRET, previousSecret, ISSUER, AUDIENCE,
                List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                        "/api/v1/auth/logout", "/actuator/health", "/actuator/info"));
        JwtAuthenticationWebFilter rotationFilter = new JwtAuthenticationWebFilter(rotationProperties);

        String accountId = UUID.randomUUID().toString();
        String tokenSignedWithOldSecret = buildToken(new JWTClaimsSet.Builder()
                .subject(accountId)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), previousSecret);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenSignedWithOldSecret)
                        .build());

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        rotationFilter.filter(exchange, ex -> {
            forwarded.set(ex);
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo(accountId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String validToken(String subject, String email) {
        return buildToken(new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .claim("email", email)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build(), SECRET);
    }

    /**
     * A quote in the path used to answer with an internal error instead of a 401. The 401 body is
     * built from a {@code ProblemDetail} whose {@code instance} is set from
     * {@code URI.create(path)}, and the path was taken DECODED — so {@code %22} became a raw
     * quote, which {@code URI.create} rejects outright. The resulting
     * {@code IllegalArgumentException} propagates out of the filter and lands on
     * {@code GatewayExceptionHandler} as a 500, which any unauthenticated caller could trigger on
     * demand for every protected path on the platform.
     */
    @Test
    void aQuoteInThePathStillYieldsA401RatherThanAnInternalError() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET,
                        URI.create("/api/v1/bookings/x%22,%22role%22:%22admin")).build());

        assertThatCode(() -> filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5)))
                .doesNotThrowAnyException();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static String buildToken(JWTClaimsSet claims, String secret) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }
}
