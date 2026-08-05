package com.aireak.identity;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// jwt.issuer/audience/previous-secret/internal-secret, cors.allowed-origins and
// spring.data.redis.* back non-defaulted ${...} placeholders in application.yaml — pinned here
// for the same reason jwt.secret already was (see ApiGatewayApplicationTests).
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
		"jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
		"jwt.issuer=https://auth.aireak.com",
		"jwt.audience=aireak-platform",
		"jwt.previous-secret=",
		"jwt.internal-secret=",
		"cors.allowed-origins=http://localhost:5173",
		"spring.data.redis.host=localhost",
		"spring.data.redis.port=6379",
		"spring.data.redis.password="
})
class IdentityServiceApplicationTests {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("identity_db")
			.withUsername("aireak")
			.withPassword("aireak");

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@MockitoBean
	private RedissonClient redissonClient;

	@Test
	void contextLoads() {
	}

}
