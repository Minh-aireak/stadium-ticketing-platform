package com.aireak.gateway;

import com.aireak.gateway.config.JwtValidationProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack: real embedded gateway (random port) + real Redis (Testcontainers) + a tiny stub
 * downstream server (Reactor Netty, already on the classpath transitively) standing in for all
 * six microservices. Verifies route forwarding, health, request-size rejection and
 * downstream-timeout behavior end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiGatewayRoutingTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256!!";
    private static final String ISSUER = "identity-service";
    private static final String AUDIENCE = "stadium-clients";

    // Static field initializer: runs at class-load time, before @DynamicPropertySource needs the port.
    private static final DisposableServer STUB = HttpServer.create()
            .host("localhost")
            .port(0)
            .handle((request, response) -> {
                String path = request.uri();
                if (path.startsWith("/api/v1/notifications/")) {
                    return response.status(200)
                            .sendString(Mono.delay(Duration.ofSeconds(3)).map(tick -> "notif-ok"));
                }
                if (path.startsWith("/api/v1/matches/")) {
                    return response.status(200).sendString(Mono.just("match-ok:" + path));
                }
                return response.status(200).sendString(Mono.just("ok"));
            })
            .bindNow();

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
        String stubUrl = "http://localhost:" + STUB.port();
        registry.add("IDENTITY_SERVICE_URL", () -> stubUrl);
        registry.add("MATCH_CATALOG_SERVICE_URL", () -> stubUrl);
        registry.add("TICKET_INVENTORY_SERVICE_URL", () -> stubUrl);
        registry.add("PAYMENT_SERVICE_URL", () -> stubUrl);
        registry.add("NOTIFICATION_SERVICE_URL", () -> stubUrl);
        registry.add("BOOKING_SERVICE_URL", () -> stubUrl);
        registry.add("JWT_SECRET", () -> SECRET);
        registry.add("JWT_PREVIOUS_SECRET", () -> "");
        registry.add("JWT_ISSUER", () -> ISSUER);
        registry.add("JWT_AUDIENCE", () -> AUDIENCE);
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        // Shrunk from the 10s default purely so the timeout test doesn't take 10+ seconds.
        registry.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "2s");
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

    @LocalServerPort
    private int port;

    // Signs tokens from whatever JwtValidationProperties actually won at runtime, rather than
    // assuming @DynamicPropertySource beats OS environment variables already exported in the
    // shell (e.g. a developer's loaded .env) — property-source precedence between the two isn't
    // this test's concern, so it just asks the real bean what the effective secret/issuer/audience are.
    @Autowired
    private JwtValidationProperties jwtProperties;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Test
    void forwardsAnonymousReadRequestToMatchCatalogService() {
        client().get().uri("/api/v1/matches/42")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("match-ok:/api/v1/matches/42");
    }

    @Test
    void gatewayHealthEndpointRespondsWithoutBeingProxied() {
        client().get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rejectsMetricsEndpointForNonAdminRole() {
        String bearer = bearerFor(UUID.randomUUID().toString(), "USER");

        client().get().uri("/actuator/metrics")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void allowsMetricsEndpointForAdminRole() {
        String bearer = bearerFor(UUID.randomUUID().toString(), "ADMIN");

        client().get().uri("/actuator/metrics")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rejectsGatewayEndpointForNonAdminRole() {
        String bearer = bearerFor(UUID.randomUUID().toString(), "USER");

        client().get().uri("/actuator/gateway/routes")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsOversizedPaymentBodyWith413() {
        String bearer = bearerFor(UUID.randomUUID().toString());
        String oversizedBody = "{\"padding\":\"" + "x".repeat(150_000) + "\"}";

        client().post().uri("/api/v1/payments/charge")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(oversizedBody)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void acceptsNormalSizedPaymentBody() {
        String bearer = bearerFor(UUID.randomUUID().toString());

        client().post().uri("/api/v1/payments/charge")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\":1000,\"currency\":\"VND\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void bookingPreflightAllowsAuthorizationAndIdempotencyHeaders() {
        client().options().uri("/api/v1/bookings")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                        "authorization,content-type,idempotency-key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, value -> {
                    assertThat(value).containsIgnoringCase("authorization");
                    assertThat(value).containsIgnoringCase("content-type");
                    assertThat(value).containsIgnoringCase("idempotency-key");
                });
    }

    @Test
    void slowDownstreamTriggersGatewayTimeout() {
        String bearer = bearerFor(UUID.randomUUID().toString());

        client().post().uri("/api/v1/notifications/status")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    private String bearerFor(String accountId) {
        return bearerFor(accountId, null);
    }

    private String bearerFor(String accountId, String role) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(accountId)
                    .issuer(jwtProperties.issuer())
                    .audience(List.of(jwtProperties.audience()))
                    .claim("email", accountId + "@example.com")
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            if (role != null) {
                builder.claim("role", role);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
            jwt.sign(new MACSigner(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)));
            return "Bearer " + jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }
}
