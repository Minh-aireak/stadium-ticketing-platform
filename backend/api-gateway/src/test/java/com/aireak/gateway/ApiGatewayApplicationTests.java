package com.aireak.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// Every property below backs a non-defaulted ${...} placeholder in application.yaml (jwt.secret
// included — it must be >= 256 bits or MACVerifier fails to construct). Without this, the test
// silently depends on whatever these env vars happen to be in the ambient environment: unset in
// CI (placeholder resolution failure) or set-but-short locally (KeyLengthException), exactly like
// the sibling *ApplicationTests classes in booking-service/ticket-inventory-service/match-catalog-service
// already pin jwt.secret for the same reason.
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=identity-service",
        "jwt.audience=stadium-clients",
        "IDENTITY_SERVICE_URL=http://localhost:9081",
        "MATCH_CATALOG_SERVICE_URL=http://localhost:9082",
        "TICKET_INVENTORY_SERVICE_URL=http://localhost:9083",
        "PAYMENT_SERVICE_URL=http://localhost:9084",
        "NOTIFICATION_SERVICE_URL=http://localhost:9085",
        "BOOKING_SERVICE_URL=http://localhost:9086",
        "CORS_ALLOWED_ORIGINS=http://localhost:3000"
})
class ApiGatewayApplicationTests {

	@Container
	static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", redis::getHost);
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
	}

	@Test
	void contextLoads() {
	}

}
