package com.aireak.booking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// jwt.issuer/audience/previous-secret/internal-secret, spring.kafka.bootstrap-servers,
// spring.data.redis.* and services.*.base-url back non-defaulted ${...} placeholders in
// application.yaml — pinned here for the same reason jwt.secret already was (see
// ApiGatewayApplicationTests).
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=https://auth.aireak.com",
        "jwt.audience=aireak-platform",
        "jwt.previous-secret=",
        "jwt.internal-secret=",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=",
        "services.ticket-inventory.base-url=http://localhost:8083",
        "services.payment.base-url=http://localhost:8084"
})
class BookingServiceApplicationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("booking_db")
            .withUsername("aireak")
            .withPassword("aireak");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.redisson.api.RedissonClient redissonClient;

    @Test
    void contextLoads() {
    }
}
