package com.aireak.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The only check that this service's context actually starts. It was {@code @Disabled} on the
 * grounds of needing a Postgres on a fixed local port — the same need every other service's
 * context test resolves with Testcontainers, which is what it now does too.
 *
 * <p>Worth having beyond tidiness: payment-service consumes Kafka (see {@code KafkaConfig} and
 * {@code RefundRequestedConsumer}), and a mistake in that wiring shows up as a service that will
 * not boot. Without this test the whole test suite could pass while the container crash-loops.
 *
 * <p>The Kafka address is deliberately one nothing listens on: listener containers retry their
 * connection in the background and do not hold up startup, so this asserts the beans wire together
 * without needing a broker. The jwt.*, stripe.* and services.* values back non-defaulted
 * {@code ${...}} placeholders in application.yaml — dummies, since nothing is called during
 * startup (same approach as NotificationServiceApplicationTests).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=https://auth.aireak.com",
        "jwt.audience=aireak-platform",
        "jwt.previous-secret=",
        "jwt.internal-secret=",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "stripe.secret-key=sk_test_dummy",
        "stripe.webhook-secret=whsec_dummy",
        "services.booking.base-url=http://localhost:8086"
})
class PaymentServiceApplicationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
            .withUsername("aireak")
            .withPassword("aireak");

    // Redisson connects eagerly while its bean is being built, so unlike Kafka this one does need
    // a live server for the context to come up at all. --requirepass for the reason spelled out in
    // api-gateway's RateLimitingWebFilterIntegrationTest: Redisson sends AUTH unconditionally once
    // spring.data.redis.password resolves to anything, even empty, and a password-less Redis
    // rejects AUTH outright.
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "test-redis-password");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-redis-password");
    }

    @Test
    void contextLoads() {
    }

}
