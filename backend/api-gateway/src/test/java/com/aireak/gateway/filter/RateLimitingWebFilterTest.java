package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import com.aireak.gateway.ratelimit.RateLimitPolicy;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fast, pure-unit companion to {@link RateLimitingWebFilterIntegrationTest}: every
 * {@link RedisRateLimiter} is mocked, so this only exercises the filter's own routing/key-
 * resolution logic (policy selection, key trust, fail-open signal detection) — not the real
 * Redis token-bucket behavior, which the Testcontainers-backed integration test already covers.
 */
class RateLimitingWebFilterTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    private final JwtValidationProperties jwtProperties = new JwtValidationProperties(
            SECRET, null, ISSUER, AUDIENCE,
            List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                    "/api/v1/auth/logout", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                    "GET:/api/v1/auth/verify-email",
                    "/actuator/health", "/actuator/info"));
    private final com.aireak.gateway.config.GatewayProperties gatewayProperties = new com.aireak.gateway.config.GatewayProperties(
            List.of("127.0.0.1", "10.0.0.0/8", "203.0.113.99"));


    private Map<RateLimitPolicy, RedisRateLimiter> limiters;
    private MeterRegistry meterRegistry;
    private RateLimitingWebFilter filter;

    @BeforeEach
    void setUp() {
        limiters = new EnumMap<>(RateLimitPolicy.class);
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            limiters.put(policy, mock(RedisRateLimiter.class));
        }
        meterRegistry = new SimpleMeterRegistry();
        filter = new RateLimitingWebFilter(limiters, jwtProperties, gatewayProperties, meterRegistry);
    }

    private void allow(RateLimitPolicy policy) {
        when(limiters.get(policy).isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(true,
                        Map.of(RedisRateLimiter.REMAINING_HEADER, "9"))));
    }

    private void deny(RateLimitPolicy policy) {
        when(limiters.get(policy).isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of())));
    }

    @Test
    void healthEndpointBypassesRateLimitingEntirelyWithoutTouchingAnyLimiter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
        limiters.values().forEach(l -> Mockito.verifyNoInteractions(l));
    }

    @Test
    void bookingPathResolvesToTheBookingPolicyKeyedByTrustedUserId() {
        allow(RateLimitPolicy.BOOKING);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header("X-User-Id", "user-42")
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.BOOKING)).isAllowed("BOOKING", "user:user-42");
    }

    @Test
    void logoutHasAnExplicitPolicyKeyedTheSameWayAsRefresh() {
        allow(RateLimitPolicy.REFRESH_TOKEN);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/logout")
                        .remoteAddress(new InetSocketAddress("203.0.113.99", 4321))
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.REFRESH_TOKEN)).isAllowed("REFRESH_TOKEN", "ip:203.0.113.99");
        verify(limiters.get(RateLimitPolicy.DEFAULT_ANONYMOUS), never()).isAllowed(anyString(), anyString());
    }

    /**
     * Guards the reason these two paths are listed at all: both are public, so a missing entry
     * would silently downgrade them to DEFAULT_ANONYMOUS (~30 requests straight off) instead of
     * the email-sending allowance forgot-password needs.
     */
    @Test
    void forgotPasswordIsKeyedByIpUnderItsOwnEmailSendingPolicy() {
        allow(RateLimitPolicy.PASSWORD_RESET_REQUEST);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/forgot-password")
                        .remoteAddress(new InetSocketAddress("203.0.113.7", 5555))
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.PASSWORD_RESET_REQUEST))
                .isAllowed("PASSWORD_RESET_REQUEST", "ip:203.0.113.7");
        verify(limiters.get(RateLimitPolicy.DEFAULT_ANONYMOUS), never()).isAllowed(anyString(), anyString());
    }

    @Test
    void resetPasswordIsKeyedByIpUnderTheLooserConfirmPolicy() {
        allow(RateLimitPolicy.PASSWORD_RESET_CONFIRM);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/reset-password")
                        .remoteAddress(new InetSocketAddress("203.0.113.8", 5556))
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.PASSWORD_RESET_CONFIRM))
                .isAllowed("PASSWORD_RESET_CONFIRM", "ip:203.0.113.8");
    }

    /**
     * Same guard as the two above, for the path a customer reaches by clicking the link in their
     * verification email. It is public, so a missing entry drops it to DEFAULT_ANONYMOUS; it must
     * also not land on VERIFICATION_RESEND, whose one-per-20-minutes email allowance would strand
     * anyone whose mail client prefetches the link before they click it.
     */
    @Test
    void verifyEmailIsKeyedByIpUnderItsOwnConfirmPolicy() {
        allow(RateLimitPolicy.EMAIL_VERIFICATION_CONFIRM);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/verify-email?token=tok-abc")
                        .remoteAddress(new InetSocketAddress("203.0.113.9", 5557))
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.EMAIL_VERIFICATION_CONFIRM))
                .isAllowed("EMAIL_VERIFICATION_CONFIRM", "ip:203.0.113.9");
        verify(limiters.get(RateLimitPolicy.VERIFICATION_RESEND), never()).isAllowed(anyString(), anyString());
        verify(limiters.get(RateLimitPolicy.DEFAULT_ANONYMOUS), never()).isAllowed(anyString(), anyString());
    }

    @Test
    void unknownProtectedPathWithTrustedUserIdFallsBackToDefaultAuthenticated() {
        allow(RateLimitPolicy.DEFAULT_AUTHENTICATED);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/some-new-endpoint")
                        .header("X-User-Id", "user-7")
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.DEFAULT_AUTHENTICATED))
                .isAllowed("DEFAULT_AUTHENTICATED", "user:user-7");
    }

    @Test
    void deniedRequestReturns429WithRetryAfterAndJsonBodyAndIncrementsMetric() {
        deny(RateLimitPolicy.PAYMENT);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/payments/123")
                        .header("X-User-Id", "user-1")
                        .build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("6");
        assertThat(meterRegistry.counter("gateway.ratelimit.exceeded", "policy", "PAYMENT", "keyType", "USER")
                .count()).isEqualTo(1.0);
    }

    @Test
    void redisUnavailableSignalFailsOpenAndIncrementsMetricWithoutBlockingTheRequest() {
        // RedisRateLimiter.isAllowed() itself swallows Redis/Lua errors and returns allowed=true
        // with REMAINING_HEADER=-1 as the only observable trace — this simulates exactly that.
        when(limiters.get(RateLimitPolicy.LOGIN).isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(true,
                        Map.of(RedisRateLimiter.REMAINING_HEADER, "-1"))));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("203.0.113.5", 1111))
                        .build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
        assertThat(meterRegistry.counter("gateway.ratelimit.redis.unavailable", "policy", "LOGIN").count())
                .isEqualTo(1.0);
    }

    @Test
    void refreshTokenPathWithAValidBearerTokenIsKeyedByVerifiedUserIdNotIp() {
        allow(RateLimitPolicy.REFRESH_TOKEN);
        String accountId = UUID.randomUUID().toString();
        String token;
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(accountId)
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            token = jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .remoteAddress(new InetSocketAddress("203.0.113.6", 2222))
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.REFRESH_TOKEN))
                .isAllowed("REFRESH_TOKEN", "user:" + accountId);
    }

    @Test
    void refreshTokenPathWithoutABearerTokenFallsBackToIp() {
        allow(RateLimitPolicy.REFRESH_TOKEN);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/refresh")
                        .remoteAddress(new InetSocketAddress("203.0.113.7", 3333))
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.REFRESH_TOKEN))
                .isAllowed("REFRESH_TOKEN", "ip:203.0.113.7");
    }

    @Test
    void refreshTokenPathWithAnExpiredBearerTokenStillUsesItsSubjectClaim() {
        // tryVerifySubjectFromBearer() deliberately accepts an expired-but-signature-valid
        // token here: the refresh flow's own token itself may be expiring, that's not a reason
        // to fall back to a coarser IP-keyed bucket for a caller we can still identify.
        allow(RateLimitPolicy.REFRESH_TOKEN);
        String accountId = UUID.randomUUID().toString();
        String expiredToken;
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(accountId)
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .issueTime(Date.from(Instant.now().minusSeconds(600)))
                    .expirationTime(Date.from(Instant.now().minusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            expiredToken = jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                        .remoteAddress(new InetSocketAddress("203.0.113.8", 4444))
                        .build());

        filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.REFRESH_TOKEN))
                .isAllowed("REFRESH_TOKEN", "user:" + accountId);
    }

    @Test
    void readsXForwardedForOnlyWhenRequestComesFromTrustedProxy() {
        allow(RateLimitPolicy.REFRESH_TOKEN);
        MockServerWebExchange trustedExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/refresh")
                        .remoteAddress(new InetSocketAddress("203.0.113.99", 5555))
                        .header("X-Forwarded-For", "198.51.100.44, 203.0.113.99")
                        .build());

        filter.filter(trustedExchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.REFRESH_TOKEN))
                .isAllowed("REFRESH_TOKEN", "ip:198.51.100.44");
    }

    @Test
    void ignoresXForwardedForWhenRequestComesFromUntrustedRemoteAddress() {
        allow(RateLimitPolicy.REFRESH_TOKEN);
        MockServerWebExchange untrustedExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/refresh")
                        .remoteAddress(new InetSocketAddress("198.51.100.5", 5555))
                        .header("X-Forwarded-For", "1.1.1.1")
                        .build());

        filter.filter(untrustedExchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        verify(limiters.get(RateLimitPolicy.REFRESH_TOKEN))
                .isAllowed("REFRESH_TOKEN", "ip:198.51.100.5");
    }
}
