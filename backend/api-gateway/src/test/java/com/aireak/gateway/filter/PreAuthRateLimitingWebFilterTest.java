package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import com.aireak.gateway.ratelimit.RateLimitPolicy;
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
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the gap fixed alongside JwtAuthenticationWebFilter's rejection short-circuit: this
 * filter runs before JWT validation (order -60, JwtAuthenticationWebFilter is -50), so it caps
 * an IP regardless of whether the request would ultimately be authenticated or rejected —
 * including a flood of garbage/expired bearer tokens that would otherwise never reach any
 * rate limiter. Also proves the narrower scoping: public paths (where RateLimitingWebFilter
 * already runs unconditionally, since JwtAuthenticationWebFilter never short-circuits them) skip
 * this filter's Redis round-trip entirely.
 */
class PreAuthRateLimitingWebFilterTest {

    private static final JwtValidationProperties JWT_PROPERTIES = new JwtValidationProperties(
            "test-secret-key-at-least-32-bytes-long-for-hs256!!", null, "identity-service", "stadium-clients",
            List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                    "/api/v1/auth/logout", "/api/v1/matches/**", "/actuator/health", "/actuator/info"));
    private static final com.aireak.gateway.config.GatewayProperties GATEWAY_PROPERTIES = new com.aireak.gateway.config.GatewayProperties(
            List.of("127.0.0.1", "10.0.0.0/8"));

    private Map<RateLimitPolicy, RedisRateLimiter> limiters;
    private RedisRateLimiter preAuthLimiter;
    private MeterRegistry meterRegistry;
    private PreAuthRateLimitingWebFilter filter;

    @BeforeEach
    void setUp() {
        limiters = new EnumMap<>(RateLimitPolicy.class);
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            limiters.put(policy, mock(RedisRateLimiter.class));
        }
        preAuthLimiter = limiters.get(RateLimitPolicy.PRE_AUTH_IP);
        meterRegistry = new SimpleMeterRegistry();
        filter = new PreAuthRateLimitingWebFilter(limiters, JWT_PROPERTIES, GATEWAY_PROPERTIES, meterRegistry);
    }

    @Test
    void repeatedRequestsFromTheSameIpThatWouldEndIn401AreStillCappedBeforeReachingTheChain() {
        when(preAuthLimiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of())));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage-token")
                        .remoteAddress(new InetSocketAddress("203.0.113.10", 5555))
                        .build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(meterRegistry.counter("gateway.ratelimit.exceeded",
                "policy", "PRE_AUTH_IP", "keyType", "IP").count()).isEqualTo(1.0);
    }

    @Test
    void allowedProtectedPathRequestIsKeyedByIpAndForwardedToTheChainWithHeaders() {
        when(preAuthLimiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(true,
                        Map.of(RedisRateLimiter.REMAINING_HEADER, "599"))));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/1")
                        .remoteAddress(new InetSocketAddress("203.0.113.11", 6666))
                        .build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
        verify(preAuthLimiter).isAllowed("PRE_AUTH_IP", "ip:203.0.113.11");
        assertThat(exchange.getResponse().getHeaders().getFirst(RedisRateLimiter.REMAINING_HEADER))
                .isEqualTo("599");
    }

    @Test
    void healthEndpointBypassesThePreAuthLimiterEntirely() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
        Mockito.verifyNoInteractions(preAuthLimiter);
    }

    // RateLimitingWebFilter already rate-limits every request on a public path unconditionally
    // (JwtAuthenticationWebFilter never short-circuits them), so a second Redis round-trip here
    // would only add latency, not protection.
    @Test
    void publicNonHealthPathAlsoBypassesThePreAuthLimiterEntirely() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("203.0.113.13", 8888))
                        .build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
        Mockito.verifyNoInteractions(preAuthLimiter);
    }

    @Test
    void redisUnavailableSignalFailsOpenAndIncrementsMetricWithoutBlockingTheRequest() {
        when(preAuthLimiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(true,
                        Map.of(RedisRateLimiter.REMAINING_HEADER, "-1"))));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/1")
                        .remoteAddress(new InetSocketAddress("203.0.113.12", 7777))
                        .build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
        assertThat(meterRegistry.counter("gateway.ratelimit.redis.unavailable", "policy", "PRE_AUTH_IP").count())
                .isEqualTo(1.0);
    }
}
