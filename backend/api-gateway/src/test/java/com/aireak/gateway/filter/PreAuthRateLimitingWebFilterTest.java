package com.aireak.gateway.filter;

import com.aireak.gateway.config.JwtValidationProperties;
import com.aireak.gateway.ratelimit.LocalIpTokenBucketLimiter;
import com.aireak.gateway.ratelimit.RateLimitPolicy;
import com.google.common.base.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the gap fixed alongside JwtAuthenticationWebFilter's rejection short-circuit: this
 * filter runs before JWT validation (order -60, JwtAuthenticationWebFilter is -50), so it caps
 * an IP regardless of whether the request would ultimately be authenticated or rejected —
 * including a flood of garbage/expired bearer tokens that would otherwise never reach any
 * rate limiter. Also proves the narrower scoping: public paths (where RateLimitingWebFilter
 * already runs unconditionally, since JwtAuthenticationWebFilter never short-circuits them) skip
 * this filter entirely.
 *
 * <p>Since the bucket moved out of Redis and into the JVM, these tests also pin the part that
 * must NOT have changed: a request over the limit still gets exactly the 429 the Redis-backed
 * version produced, down to the Retry-After value and the JSON body.
 */
class PreAuthRateLimitingWebFilterTest {

    private static final JwtValidationProperties JWT_PROPERTIES = new JwtValidationProperties(
            "test-secret-key-at-least-32-bytes-long-for-hs256!!", null, "identity-service", "stadium-clients",
            List.of("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                    "/api/v1/auth/logout", "/api/v1/matches/**", "/actuator/health", "/actuator/info"));
    private static final com.aireak.gateway.config.GatewayProperties GATEWAY_PROPERTIES = new com.aireak.gateway.config.GatewayProperties(
            List.of("127.0.0.1", "10.0.0.0/8"));

    /** PRE_AUTH_IP: burst 600 at 3 tokens per request — 200 requests drain a full bucket exactly. */
    private static final int REQUESTS_TO_DRAIN_BUCKET =
            (int) (RateLimitPolicy.PRE_AUTH_IP.burstCapacity() / RateLimitPolicy.PRE_AUTH_IP.requestedTokens());

    /** Hand-cranked clock, so "the bucket refilled" is asserted rather than waited for. */
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return nanos.get();
        }
    };

    private MeterRegistry meterRegistry;
    private LocalIpTokenBucketLimiter limiter;
    private PreAuthRateLimitingWebFilter filter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        limiter = new LocalIpTokenBucketLimiter(RateLimitPolicy.PRE_AUTH_IP, 1, 1_000, ticker);
        filter = new PreAuthRateLimitingWebFilter(limiter, JWT_PROPERTIES, GATEWAY_PROPERTIES, meterRegistry);
    }

    private static MockServerWebExchange protectedRequestFrom(String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage-token")
                        .remoteAddress(new InetSocketAddress(ip, 5555))
                        .build());
    }

    /** Returns whether the request reached the chain, i.e. was not shed. */
    private boolean runFilter(MockServerWebExchange exchange) {
        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));
        return chainInvoked[0];
    }

    private void drainBucketFor(String ip) {
        for (int i = 0; i < REQUESTS_TO_DRAIN_BUCKET; i++) {
            runFilter(protectedRequestFrom(ip));
        }
    }

    @Test
    void repeatedRequestsFromTheSameIpThatWouldEndIn401AreStillCappedBeforeReachingTheChain() {
        drainBucketFor("203.0.113.10");

        MockServerWebExchange overTheLimit = protectedRequestFrom("203.0.113.10");
        assertThat(runFilter(overTheLimit)).isFalse();
        assertThat(overTheLimit.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(meterRegistry.counter("gateway.ratelimit.exceeded",
                "policy", "PRE_AUTH_IP", "keyType", "IP").count()).isEqualTo(1.0);
    }

    /**
     * The whole point of the Redis-to-local swap is that a client cannot tell: same status, same
     * Retry-After (requestedTokens/replenishRate = ceil(3/20) = 1s), same body.
     */
    @Test
    void rejectionKeepsTheExactResponseShapeTheRedisBackedVersionProduced() {
        drainBucketFor("203.0.113.14");

        MockServerWebExchange exchange = protectedRequestFrom("203.0.113.14");
        runFilter(exchange);

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(String.valueOf(RateLimitPolicy.PRE_AUTH_IP.retryAfterSeconds()))
                .isEqualTo("1");
        assertThat(exchange.getResponse().getHeaders().getContentType()).hasToString("application/json");
        assertThat(exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5)))
                .isEqualTo("{\"error\":\"rate_limit_exceeded\",\"retryAfterSeconds\":1}");
    }

    @Test
    void bucketRefillsOverTimeSoALimitedIpRecovers() {
        drainBucketFor("203.0.113.15");
        assertThat(runFilter(protectedRequestFrom("203.0.113.15"))).isFalse();

        // Retry-After told the client to come back in a second; at 20 tokens/s that is more than
        // the 3 one request costs, so the promise has to hold.
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(runFilter(protectedRequestFrom("203.0.113.15"))).isTrue();
    }

    @Test
    void eachIpGetsItsOwnBucket() {
        drainBucketFor("203.0.113.16");

        assertThat(runFilter(protectedRequestFrom("203.0.113.16"))).isFalse();
        assertThat(runFilter(protectedRequestFrom("203.0.113.17"))).isTrue();
    }

    @Test
    void allowedProtectedPathRequestIsForwardedToTheChainWithTheSameRateLimitHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/1")
                        .remoteAddress(new InetSocketAddress("203.0.113.11", 6666))
                        .build());

        assertThat(runFilter(exchange)).isTrue();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst(RedisRateLimiter.REMAINING_HEADER)).isEqualTo("597");
        assertThat(headers.getFirst(RedisRateLimiter.REPLENISH_RATE_HEADER)).isEqualTo("20");
        assertThat(headers.getFirst(RedisRateLimiter.BURST_CAPACITY_HEADER)).isEqualTo("600");
        assertThat(headers.getFirst(RedisRateLimiter.REQUESTED_TOKENS_HEADER)).isEqualTo("3");
    }

    @Test
    void healthEndpointBypassesThePreAuthLimiterEntirely() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        assertThat(runFilter(exchange)).isTrue();
        assertThat(limiter.trackedKeys()).isZero();
    }

    // RateLimitingWebFilter already rate-limits every request on a public path unconditionally
    // (JwtAuthenticationWebFilter never short-circuits them), so a second bucket here would only
    // add cost, not protection.
    @Test
    void publicNonHealthPathAlsoBypassesThePreAuthLimiterEntirely() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("203.0.113.13", 8888))
                        .build());

        assertThat(runFilter(exchange)).isTrue();
        assertThat(limiter.trackedKeys()).isZero();
    }
}
