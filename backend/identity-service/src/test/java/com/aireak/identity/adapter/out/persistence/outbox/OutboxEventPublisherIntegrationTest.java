package com.aireak.identity.adapter.out.persistence.outbox;

import com.aireak.identity.adapter.out.persistence.AccountPersistenceAdapter;
import com.aireak.identity.application.port.in.command.RegisterAccountCommand;
import com.aireak.identity.application.port.out.EmailVerificationTokenPort;
import com.aireak.identity.application.port.out.PasswordHashPort;
import com.aireak.identity.application.service.RegisterAccountService;
import com.aireak.identity.config.JpaConfig;
import com.aireak.identity.domain.model.AccountId;
import com.aireak.identity.domain.model.HashedPassword;
import com.aireak.identity.domain.model.RawPassword;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the core outbox guarantee: writing the Account aggregate and its
 * outbox row happen in the same DB transaction. Runs against a real Postgres
 * (via Testcontainers) with Flyway migrations applied — Debezium/Kafka are
 * out of scope here (see identity-service/infra/debezium/README.md for the
 * manual end-to-end CDC check).
 *
 * <p>{@code @DataJpaTest}'s slice only imports {@code DataJpaRepositoriesAutoConfiguration} and
 * {@code HibernateJpaAutoConfiguration} — Flyway is NOT part of it, so migrations are run
 * explicitly in {@link #migrateSchema()} before the Spring context (and Hibernate's
 * {@code ddl-auto: validate}) starts; otherwise schema validation fails with
 * "missing table [accounts]" against the empty Testcontainers Postgres.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaConfig.class, // @EnableJpaAuditing — without it, BaseAuditEntity's updatedAt is
                          // never populated and every insert fails a NOT NULL constraint.
        AccountPersistenceAdapter.class,
        OutboxEventPublisher.class,
        RegisterAccountService.class,
        OutboxEventPublisherIntegrationTest.TestSupportConfig.class
})
class OutboxEventPublisherIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("identity_db")
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
    private RegisterAccountService registerAccountService;

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Test
    void registeringAccountWritesOutboxRowInSameTransaction() {
        registerAccountService.execute(new RegisterAccountCommand("outbox-test@example.com", "S3cret!Passw0rd"));

        List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

        assertThat(rows).hasSize(1);
        OutboxEventEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("identity.account.registered");
        assertThat(row.getEventType()).isEqualTo("AccountRegisteredEvent");
        assertThat(row.getPayload()).contains("AccountRegisteredEvent", "outbox-test@example.com", "test-verification-token");
    }

    @TestConfiguration
    static class TestSupportConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        PasswordHashPort passwordHashPort() {
            return new PasswordHashPort() {
                @Override
                public HashedPassword hash(RawPassword rawPassword) {
                    return new HashedPassword("hashed:" + rawPassword.exposeForHashing());
                }

                @Override
                public boolean matches(RawPassword rawPassword, HashedPassword hashedPassword) {
                    return hashedPassword.value().equals("hashed:" + rawPassword.exposeForHashing());
                }
            };
        }

        // Real token storage needs Redis, out of scope for this @DataJpaTest slice — this test
        // only verifies the outbox row written alongside the Account insert.
        @Bean
        EmailVerificationTokenPort emailVerificationTokenPort() {
            return new EmailVerificationTokenPort() {
                @Override
                public String generate() {
                    return "test-verification-token";
                }

                @Override
                public void store(String rawToken, AccountId accountId) {
                    // no-op
                }

                @Override
                public AccountId consume(String rawToken) {
                    throw new UnsupportedOperationException("not used in this test");
                }
            };
        }
    }
}
