package com.aireak.catalog;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.test.context.TestPropertySource;

// jwt.issuer/audience/previous-secret/internal-secret, spring.kafka.bootstrap-servers,
// elasticsearch.* and spring.data.redis.* back non-defaulted ${...} placeholders in
// application.yaml — pinned here for the same reason jwt.secret already was (see
// ApiGatewayApplicationTests). elasticsearch.* is still read eagerly by
// ElasticsearchClientConfig's RestClient @Bean even though ElasticsearchClient itself is mocked
// below (mocking one bean doesn't skip a separate @Bean method that feeds it); same reasoning for
// pinning spring.data.redis.* alongside mocking RedissonClient (see BookingServiceApplicationTests).
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-at-least-32-bytes-long-for-hs256!!",
        "jwt.issuer=https://auth.aireak.com",
        "jwt.audience=aireak-platform",
        "jwt.previous-secret=",
        "jwt.internal-secret=",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "elasticsearch.host=localhost",
        "elasticsearch.port=9200",
        "elasticsearch.scheme=http",
        "elasticsearch.username=test",
        "elasticsearch.password=test",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.password="
})
class MatchCatalogServiceApplicationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog_db")
            .withUsername("aireak")
            .withPassword("aireak");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    private ElasticsearchClient elasticsearchClient;

    @MockitoBean
    private org.redisson.api.RedissonClient redissonClient;

    @Test
    void contextLoads() {
    }
}
