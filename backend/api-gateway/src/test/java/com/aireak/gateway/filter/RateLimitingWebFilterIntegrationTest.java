package com.aireak.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ServerWebExchange;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link RateLimitingWebFilter} against a real Redis instance (Testcontainers) —
 * the same "test against real infra, not a mock" pattern this project already uses for
 * Postgres. Downstream service URLs are dummy values: this test never reaches the routing
 * layer, only the filter.
 *
 * <p>Also the place where the split between the two rate limiters is checked against real Redis:
 * {@link PreAuthRateLimitingWebFilter} must reach it zero times, this filter must still reach it
 * every time.
 */
// Deliberately not WebEnvironment.NONE: Spring Cloud Gateway's NettyConfiguration unconditionally
// needs a ServerProperties bean, which Boot only registers when a reactive web app context
// exists (MOCK, like a real run, still creates one — it just doesn't bind a real port).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class RateLimitingWebFilterIntegrationTest {

    // --requirepass is required: Redisson's autoconfiguration sends AUTH unconditionally once
    // spring.data.redis.password resolves to anything, even "" — and a password-less Redis
    // rejects any AUTH attempt outright, so a passwordless container fails every connection.
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "test-redis-password");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-redis-password");
        registry.add("IDENTITY_SERVICE_URL", () -> "http://localhost:19081");
        registry.add("MATCH_CATALOG_SERVICE_URL", () -> "http://localhost:19082");
        registry.add("TICKET_INVENTORY_SERVICE_URL", () -> "http://localhost:19083");
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:19084");
        registry.add("NOTIFICATION_SERVICE_URL", () -> "http://localhost:19085");
        registry.add("BOOKING_SERVICE_URL", () -> "http://localhost:19086");
        registry.add("JWT_SECRET", () -> "test-secret-key-at-least-32-bytes-long-for-hs256!!");
        registry.add("JWT_PREVIOUS_SECRET", () -> "");
        registry.add("JWT_ISSUER", () -> "identity-service");
        registry.add("JWT_AUDIENCE", () -> "stadium-clients");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
    }

    @Autowired
    private RateLimitingWebFilter filter;

    @Autowired
    private PreAuthRateLimitingWebFilter preAuthFilter;

    @Test
    void healthEndpointIsNeverRateLimited() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        boolean[] chainInvoked = {false};
        filter.filter(exchange, ex -> {
            chainInvoked[0] = true;
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertThat(chainInvoked[0]).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull(); // untouched by the filter
    }

    @Test
    void ipKeyedPolicyBlocksAfterBurstCapacityAndReturns429WithRetryAfter() {
        InetSocketAddress client = new InetSocketAddress("203.0.113.10", 12345);

        // LOGIN: burstCapacity=120, requestedTokens=12 -> 10 requests allowed
        for (int i = 0; i < 10; i++) {
            MockServerWebExchange exchange = loginRequest(client, null);
            filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        MockServerWebExchange denied = loginRequest(client, null);
        filter.filter(denied, ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(denied.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(denied.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("12");
        String body = decodeBody(denied);
        assertThat(body).isEqualTo("{\"error\":\"rate_limit_exceeded\",\"retryAfterSeconds\":12}");
    }

    @Test
    void userKeyedPolicyBlocksPerUserNotPerIp() {
        InetSocketAddress client = new InetSocketAddress("203.0.113.20", 22345);

        // PAYMENT: burstCapacity=60, requestedTokens=6 -> 10 requests allowed for user "alice"
        for (int i = 0; i < 10; i++) {
            MockServerWebExchange exchange = paymentRequest(client, "alice");
            filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
        MockServerWebExchange aliceDenied = paymentRequest(client, "alice");
        filter.filter(aliceDenied, ex -> Mono.empty()).block(Duration.ofSeconds(5));
        assertThat(aliceDenied.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // A different authenticated user, same IP, has an independent bucket.
        MockServerWebExchange bobAllowed = paymentRequest(client, "bob");
        filter.filter(bobAllowed, ex -> Mono.empty()).block(Duration.ofSeconds(5));
        assertThat(bobAllowed.getResponse().getStatusCode()).isNull();
    }

    @Test
    void forwardedForAndSpoofedUserIdHeadersAreIgnoredOnPublicIpKeyedPolicy() {
        InetSocketAddress client = new InetSocketAddress("203.0.113.30", 33345);

        // Same real remote address, different X-Forwarded-For and X-User-Id on every request —
        // since LOGIN is IP-keyed and a public path, both headers must be ignored: the bucket
        // is still exhausted after exactly 10 requests, proving neither header changes the key.
        for (int i = 0; i < 10; i++) {
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/v1/auth/login")
                    .remoteAddress(client)
                    .header("X-Forwarded-For", "198.51.100." + i)
                    .header("X-User-Id", "forged-user-" + i);
            MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
            filter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        MockServerWebExchange denied = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(client)
                        .header("X-Forwarded-For", "198.51.100.99")
                        .header("X-User-Id", "forged-user-99")
                        .build());
        filter.filter(denied, ex -> Mono.empty()).block(Duration.ofSeconds(5));
        assertThat(denied.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The pre-auth guard runs on every inbound request, so its Redis cost was paid on every
     * request too. It now keeps its bucket in-process; this pins that there is nothing left to
     * roll back to, measured at Redis itself rather than by trusting the wiring.
     */
    @Test
    void preAuthFilterReachesRedisZeroTimesWhilePostAuthFilterStillDoes() throws Exception {
        InetSocketAddress client = new InetSocketAddress("203.0.113.40", 44345);
        resetRedisCommandStats();

        for (int i = 0; i < 5; i++) {
            MockServerWebExchange exchange = bookingRequest(client);
            preAuthFilter.filter(exchange, ex -> Mono.empty()).block(Duration.ofSeconds(5));
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        assertThat(redisScriptCalls()).isZero();

        // Control, so a zero above means "the pre-auth bucket is local" and not "the probe is
        // broken": the post-auth filter on the same path still runs its token bucket in Redis.
        filter.filter(bookingRequest(client), ex -> Mono.empty()).block(Duration.ofSeconds(5));

        assertThat(redisScriptCalls()).isPositive();
    }

    private static MockServerWebExchange bookingRequest(InetSocketAddress remote) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/bookings/1")
                        .remoteAddress(remote)
                        .build());
    }

    private static void resetRedisCommandStats() throws Exception {
        redis.execInContainer("redis-cli", "-a", "test-redis-password", "CONFIG", "RESETSTAT");
    }

    /**
     * Both rate limiters run their token bucket as a Lua script, so EVAL/EVALSHA call counts are
     * the exact signal wanted here — and they ignore the connection keepalive traffic Redisson
     * and Lettuce generate in the background regardless of what the filters do.
     *
     * <p>The pattern is anchored on the leading {@code calls=}: an INFO line also carries
     * {@code rejected_calls} and {@code failed_calls}, which an unanchored match happily reads
     * instead.
     */
    private static final Pattern EVAL_CALLS =
            Pattern.compile("^cmdstat_eval[^:]*:calls=(\\d+)");

    private static long redisScriptCalls() throws Exception {
        String info = redis.execInContainer(
                "redis-cli", "-a", "test-redis-password", "INFO", "commandstats").getStdout();
        return info.lines()
                .map(EVAL_CALLS::matcher)
                .filter(java.util.regex.Matcher::find)
                .mapToLong(matcher -> Long.parseLong(matcher.group(1)))
                .sum();
    }

    private static MockServerWebExchange loginRequest(InetSocketAddress remote, String userId) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(remote);
        if (userId != null) {
            builder.header("X-User-Id", userId);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private static MockServerWebExchange paymentRequest(InetSocketAddress remote, String userId) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/payments/123")
                        .remoteAddress(remote)
                        .header("X-User-Id", userId)
                        .build());
    }

    private static String decodeBody(ServerWebExchange exchange) {
        return ((org.springframework.mock.http.server.reactive.MockServerHttpResponse) exchange.getResponse())
                .getBodyAsString()
                .block(Duration.ofSeconds(5));
    }
}
