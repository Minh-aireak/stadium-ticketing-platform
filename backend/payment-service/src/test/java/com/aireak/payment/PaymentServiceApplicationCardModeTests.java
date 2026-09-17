package com.aireak.payment;

import com.aireak.payment.adapter.in.scheduling.PaymentWindowExpiryJob;
import com.aireak.payment.config.PaymentModeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Card-mode twin of {@link PaymentServiceApplicationTests}: same containers, {@code payment.mode=card},
 * and one assertion the auto-mode test cannot make -- that the window-expiry job is wired in when
 * the mode asks for it. Property binding for {@code payment.card.*} is checked here too, since a
 * record-based {@code @ConfigurationProperties} that fails to bind fails at startup.
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
        "services.booking.base-url=http://localhost:8086",
        "payment.mode=card",
        "payment.card.window-minutes=8"
})
class PaymentServiceApplicationCardModeTests {

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

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PaymentModeProperties paymentMode;

    // The expiry job is @ConditionalOnProperty(payment.mode=card); in auto mode there is nothing
    // for it to do and it must not exist -- PaymentServiceApplicationTests is the auto-mode half.
    @Test
    void cardModeRegistersTheExpiryJobAndBindsTheWindow() {
        assertThat(paymentMode.isCardMode()).isTrue();
        assertThat(paymentMode.window()).isEqualTo(Duration.ofMinutes(8));
        assertThat(context.getBeanNamesForType(PaymentWindowExpiryJob.class)).hasSize(1);
    }

}
