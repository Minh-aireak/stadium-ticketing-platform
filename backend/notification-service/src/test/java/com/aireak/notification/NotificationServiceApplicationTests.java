package com.aireak.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// jwt.issuer/audience/previous-secret/internal-secret, spring.kafka.bootstrap-servers,
// app.*-base-url and brevo.* back non-defaulted ${...} placeholders in application.yaml — pinned
// here for the same reason jwt.secret already was (see ApiGatewayApplicationTests). The brevo.*
// values are dummies: this only asserts the context wires up, and no email is sent during it.
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
		"jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
		"jwt.issuer=https://auth.aireak.com",
		"jwt.audience=aireak-platform",
		"jwt.previous-secret=",
		"jwt.internal-secret=",
		"spring.kafka.bootstrap-servers=localhost:9092",
		"app.public-api-base-url=http://localhost:8080",
		"app.frontend-base-url=http://localhost:5173",
		"brevo.api-key=test-brevo-api-key",
		"brevo.sender-email=no-reply@stadium.test",
		"brevo.sender-name=Stadium Ticketing"
})
class NotificationServiceApplicationTests {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("notification_db")
			.withUsername("aireak")
			.withPassword("aireak");

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Test
	void contextLoads() {
	}

}
