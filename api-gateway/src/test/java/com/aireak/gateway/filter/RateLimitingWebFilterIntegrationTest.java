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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link RateLimitingWebFilter} against a real Redis instance (Testcontainers) —
 * the same "test against real infra, not a mock" pattern this project already uses for
 * Postgres. Downstream service URLs are dummy values: this test never reaches the routing
 * layer, only the filter.
 */
// Deliberately not WebEnvironment.NONE: Spring Cloud Gateway's NettyConfiguration unconditionally
// needs a ServerProperties bean, which Boot only registers when a reactive web app context
// exists (MOCK, like a real run, still creates one — it just doesn't bind a real port).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
class RateLimitingWebFilterIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("IDENTITY_SERVICE_URL", () -> "http://localhost:19081");
        registry.add("MATCH_CATALOG_SERVICE_URL", () -> "http://localhost:19082");
        registry.add("TICKET_INVENTORY_SERVICE_URL", () -> "http://localhost:19083");
        registry.add("PAYMENT_SERVICE_URL", () -> "http://localhost:19084");
        registry.add("NOTIFICATION_SERVICE_URL", () -> "http://localhost:19085");
        registry.add("BOOKING_SERVICE_URL", () -> "http://localhost:19086");
        registry.add("JWT_SECRET", () -> "test-secret-key-at-least-32-bytes-long-for-hs256!!");
        registry.add("JWT_ISSUER", () -> "identity-service");
        registry.add("JWT_AUDIENCE", () -> "stadium-clients");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
    }

    @Autowired
    private RateLimitingWebFilter filter;

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
