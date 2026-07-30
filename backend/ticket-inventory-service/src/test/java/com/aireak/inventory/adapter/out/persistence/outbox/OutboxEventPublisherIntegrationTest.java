package com.aireak.inventory.adapter.out.persistence.outbox;

import com.aireak.inventory.adapter.out.persistence.SeatInventoryPersistenceAdapter;
import com.aireak.inventory.application.port.out.DomainEventPublisher;
import com.aireak.inventory.application.port.out.SeatInventoryRepository;
import com.aireak.inventory.config.InfraConfig;
import com.aireak.inventory.domain.model.Seat;
import com.aireak.inventory.domain.model.SeatCode;
import com.aireak.inventory.domain.model.SeatInventory;
import com.aireak.inventory.domain.model.SeatTier;
import tools.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the core outbox guarantee: writing the SeatInventory aggregate and its outbox row
 * happen in the same DB transaction. Runs against a real Postgres (via Testcontainers) with
 * Flyway migrations applied — Debezium/Kafka are out of scope here (see
 * ticket-inventory-service/infra/debezium/README.md for the manual end-to-end CDC check).
 *
 * <p>{@code @DataJpaTest}'s slice only imports {@code DataJpaRepositoriesAutoConfiguration} and
 * {@code HibernateJpaAutoConfiguration} — Flyway is NOT part of it, so migrations are run
 * explicitly in {@link #migrateSchema()} before the Spring context (and Hibernate's
 * {@code ddl-auto: validate}) starts.
 *
 * <p>Only {@code SeatsSoldEvent} is covered here: {@link SeatInventory#sellSeats} is the only
 * aggregate mutation that writes to Postgres — reserve/release ({@code SeatInventoryService})
 * hold seats in Redis only and never touch this aggregate or the outbox table (see
 * {@link OutboxEventPublisher} javadoc).
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        InfraConfig.class, // @EnableJpaAuditing — without it, BaseAuditEntity's updatedAt is
                            // never populated and every insert fails a NOT NULL constraint.
        SeatInventoryPersistenceAdapter.class,
        OutboxEventPublisher.class,
        OutboxEventPublisherIntegrationTest.TestSupportConfig.class
})
class OutboxEventPublisherIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_db")
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
    private SeatInventoryRepository seatInventoryRepository;

    @Autowired
    private DomainEventPublisher eventPublisher;

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Test
    void confirmingSeatsSoldWritesOutboxRowInSameTransaction() {
        SeatCode seatA1 = new SeatCode("A1");
        SeatCode seatA2 = new SeatCode("A2");
        BigDecimal price = new BigDecimal("150000");
        SeatInventory inventory = SeatInventory.create("showtime-1", List.of(
                new Seat(seatA1, SeatTier.STANDARD, price),
                new Seat(seatA2, SeatTier.STANDARD, price)));
        inventory.sellSeats(List.of(seatA1, seatA2), "booking-1");

        seatInventoryRepository.save(inventory);
        eventPublisher.publishAll(inventory.pullDomainEvents());

        List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

        assertThat(rows).hasSize(1);
        OutboxEventEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("inventory.seats.sold");
        assertThat(row.getAggregateId()).isEqualTo("showtime-1");
        assertThat(row.getEventType()).isEqualTo("SeatsSoldEvent");
        assertThat(row.getPayload()).contains("SeatsSoldEvent", "showtime-1", "A1", "A2");
    }

    @TestConfiguration
    static class TestSupportConfig {

        @Bean
        JsonMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules(
                    OutboxEventPublisherIntegrationTest.class.getClassLoader()).build();
        }
    }
}
