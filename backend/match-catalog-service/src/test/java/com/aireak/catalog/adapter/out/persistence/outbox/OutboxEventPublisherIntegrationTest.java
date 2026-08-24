package com.aireak.catalog.adapter.out.persistence.outbox;

import com.aireak.catalog.adapter.out.persistence.MatchPersistenceAdapter;
import com.aireak.catalog.application.port.out.MatchSearchPort;
import com.aireak.catalog.application.service.MatchCatalogService;
import com.aireak.catalog.application.service.MatchSearchIndexer;
import com.aireak.catalog.application.service.ShowtimeSeatCounterInitializer;
import com.aireak.catalog.config.InfraConfig;
import com.aireak.catalog.domain.model.Match;
import com.aireak.common.outbox.OutboxEventEntity;
import com.aireak.common.outbox.OutboxEventJpaRepository;
import com.aireak.common.web.filter.CorrelationIdFilter;
import tools.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the core outbox guarantee: writing the Match aggregate and its outbox row happen in
 * the same DB transaction. Runs against a real Postgres (via Testcontainers) with Flyway
 * migrations applied — Elasticsearch/Debezium/Kafka are out of scope here (see
 * match-catalog-service/infra/debezium/README.md for the manual end-to-end CDC check).
 *
 * <p>{@code @DataJpaTest}'s slice only imports {@code DataJpaRepositoriesAutoConfiguration} and
 * {@code HibernateJpaAutoConfiguration} — Flyway is NOT part of it, so migrations are run
 * explicitly in {@link #migrateSchema()} before the Spring context (and Hibernate's
 * {@code ddl-auto: validate}) starts.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        InfraConfig.class, // @EnableJpaAuditing — without it, BaseAuditEntity's updatedAt is
                            // never populated and every insert fails a NOT NULL constraint.
        MatchPersistenceAdapter.class,
        OutboxConfig.class, // @EntityScan/@EnableJpaRepositories for com.aireak.common.outbox
        OutboxEventPublisher.class,
        MatchSearchIndexer.class,
        MatchCatalogService.class,
        OutboxEventPublisherIntegrationTest.TestSupportConfig.class
})
class OutboxEventPublisherIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("catalog_db")
            .withUsername("aireak")
            .withPassword("aireak");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired
    private MatchCatalogService matchCatalogService;

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Test
    void addingShowtimeWritesOutboxRowInSameTransaction() {
        String matchId = matchCatalogService.createMatch("Home FC", "Away FC", "Premier League");

        matchCatalogService.addShowtime(matchId, Instant.now().plusSeconds(3600), "my-dinh",
                new BigDecimal("150000"), "VND");

        List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

        assertThat(rows).hasSize(1);
        OutboxEventEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("catalog.showtime.created");
        assertThat(row.getEventType()).isEqualTo("ShowtimeAddedEvent");
        assertThat(row.getPayload()).contains("ShowtimeAddedEvent", matchId, "150000", "VND");
    }

    @Test
    void publishingMatchWritesOutboxRowInSameTransaction() {
        String matchId = matchCatalogService.createMatch("Home FC", "Away FC", "Premier League");
        // Match.publish() requires at least one showtime (see Match#publish invariant).
        matchCatalogService.addShowtime(matchId, Instant.now().plusSeconds(3600), "my-dinh",
                new BigDecimal("150000"), "VND");
        outboxEventJpaRepository.deleteAll(); // drop the addShowtime row so only publish()'s row is asserted below

        matchCatalogService.publishMatch(matchId);

        List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

        assertThat(rows).hasSize(1);
        OutboxEventEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("catalog.match.published");
        assertThat(row.getAggregateId()).isEqualTo(matchId);
        assertThat(row.getEventType()).isEqualTo("MatchPublishedEvent");
        assertThat(row.getPayload()).contains("MatchPublishedEvent", matchId, "Home FC", "Away FC");
    }

    @Test
    void cancellingMatchWritesOutboxRowWithShowtimeIdsAndReason() {
        String matchId = matchCatalogService.createMatch("Home FC", "Away FC", "Premier League");
        matchCatalogService.addShowtime(matchId, Instant.now().plusSeconds(3600), "my-dinh",
                new BigDecimal("150000"), "VND");
        matchCatalogService.publishMatch(matchId);
        outboxEventJpaRepository.deleteAll(); // drop prior rows so only cancelMatch()'s row is asserted below

        matchCatalogService.cancelMatch(matchId, "Stadium closed for safety inspection");

        List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

        assertThat(rows).hasSize(1);
        OutboxEventEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("catalog.match.cancelled");
        assertThat(row.getAggregateId()).isEqualTo(matchId);
        assertThat(row.getEventType()).isEqualTo("MatchCancelledEvent");
        assertThat(row.getPayload()).contains("MatchCancelledEvent", matchId, "Stadium closed for safety inspection");
    }

    /**
     * The correlation ID has to reach the row AND the envelope inside it: the row is what makes the
     * outbox searchable by trace when a message never arrives, and the envelope is what
     * {@code CorrelationIdRecordInterceptor} reads to restore the ID on the consumer side. Missing
     * it fails silently — the interceptor mints a fresh UUID, so consumer logs still carry a
     * correlationId, it just belongs to no request.
     */
    @Test
    void outboxRowCarriesTheOriginatingRequestsCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "trace-from-the-admin-request");
        try {
            String matchId = matchCatalogService.createMatch("Home FC", "Away FC", "Premier League");
            matchCatalogService.addShowtime(matchId, Instant.now().plusSeconds(3600), "my-dinh",
                    new BigDecimal("150000"), "VND");

            List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTraceId()).isEqualTo("trace-from-the-admin-request");
            assertThat(rows.get(0).getPayload()).contains("trace-from-the-admin-request");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    @TestConfiguration
    static class TestSupportConfig {

        @Bean
        JsonMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules(
                    OutboxEventPublisherIntegrationTest.class.getClassLoader()).build();
        }

        /**
         * Seeding the live seat counter is a Redis call, and Redis is out of scope for a test about
         * one Postgres transaction — {@code addShowtime} only has to reach the outbox row.
         */
        @Bean
        ShowtimeSeatCounterInitializer showtimeSeatCounterInitializer() {
            return mock(ShowtimeSeatCounterInitializer.class);
        }

        @Bean
        MatchSearchPort matchSearchPort() {
            return new MatchSearchPort() {
                @Override
                public void index(Match match) {
                    // no-op: Elasticsearch is out of scope for this outbox transaction test
                }

                @Override
                public SearchResult search(String query, int page, int size) {
                    return new SearchResult(List.of(), 0);
                }
            };
        }
    }
}
