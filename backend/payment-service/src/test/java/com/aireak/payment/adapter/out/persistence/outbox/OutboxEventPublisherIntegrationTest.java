package com.aireak.payment.adapter.out.persistence.outbox;

import com.aireak.common.web.filter.CorrelationIdFilter;
import com.aireak.payment.adapter.out.persistence.PaymentPersistenceAdapter;
import com.aireak.payment.application.port.out.DomainEventPublisher;
import com.aireak.payment.application.port.out.PaymentRepository;
import com.aireak.payment.config.JpaConfig;
import com.aireak.payment.domain.model.Payment;
import com.aireak.payment.domain.model.PaymentStatus;
import tools.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the core outbox guarantee: writing the Payment aggregate and its outbox row happen in
 * the same DB transaction. Runs against a real Postgres (via Testcontainers) with Flyway
 * migrations applied — Debezium/Kafka are out of scope here (see
 * payment-service/infra/debezium/README.md for the manual end-to-end CDC check).
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
        JpaConfig.class, // @EnableJpaAuditing — without it, BaseAuditEntity's updatedAt is
                          // never populated and every insert fails a NOT NULL constraint.
        PaymentPersistenceAdapter.class,
        OutboxEventPublisher.class,
        OutboxEventPublisherIntegrationTest.TestSupportConfig.class
})
class OutboxEventPublisherIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_db")
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
    private PaymentRepository paymentRepository;

    @Autowired
    private DomainEventPublisher eventPublisher;

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void updatingAnAlreadyPersistedPaymentUpdatesInPlace() {
        Payment payment = Payment.initiate("booking-2", "buyer@example.com", new BigDecimal("99.00"), "USD");
        String paymentId = payment.getPaymentId();

        paymentRepository.save(payment);
        // Flush + clear so the next findById() issues a real SELECT against a fresh session —
        // the same shape as production, where each PaymentSagaSteps step runs in its own
        // REQUIRES_NEW transaction. Without carrying the JPA @Version through Payment (see
        // Payment.version javadoc), PaymentPersistenceAdapter would build a fresh JPA entity with
        // version=null on the next save, Spring Data JPA would treat it as new, and this second
        // save would attempt to INSERT a duplicate paymentId instead of updating it.
        entityManager.flush();
        entityManager.clear();

        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(0L);

        reloaded.markSucceeded("gw-tx-2");
        paymentRepository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        Payment reloadedAgain = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloadedAgain.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(reloadedAgain.getGatewayTransactionId()).isEqualTo("gw-tx-2");
        assertThat(reloadedAgain.getVersion()).isEqualTo(1L);
    }

    @Test
    void markingPaymentSucceededWritesOutboxRowInSameTransaction() {
        Payment payment = Payment.initiate("booking-1", "buyer@example.com", new BigDecimal("150.00"), "USD");
        payment.pullDomainEvents(); // discard PaymentInitiatedEvent, not under test here
        payment.markSucceeded("gw-tx-1");

        paymentRepository.save(payment);
        eventPublisher.publishAll(payment.pullDomainEvents());

        List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

        assertThat(rows).hasSize(1);
        OutboxEventEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("payment.payment.succeeded");
        assertThat(row.getAggregateId()).isEqualTo(payment.getPaymentId());
        assertThat(row.getEventType()).isEqualTo("PaymentSucceededEvent");
        assertThat(row.getPayload()).contains("PaymentSucceededEvent", payment.getPaymentId(), "gw-tx-1");
    }

    /**
     * The correlation ID has to reach the row AND the envelope inside it: the row is what makes the
     * outbox searchable by trace when a message never arrives, and the envelope is what
     * {@code CorrelationIdRecordInterceptor} reads to restore the ID on the consumer side. Missing
     * it fails silently — the interceptor mints a fresh UUID, so booking-service's logs still carry
     * a correlationId, it just belongs to no request.
     */
    @Test
    void outboxRowCarriesTheOriginatingRequestsCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "trace-from-the-checkout-request");
        try {
            Payment payment = Payment.initiate("booking-1", "buyer@example.com", new BigDecimal("150.00"), "USD");
            paymentRepository.save(payment);
            eventPublisher.publishAll(payment.pullDomainEvents());

            List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTraceId()).isEqualTo("trace-from-the-checkout-request");
            assertThat(rows.get(0).getPayload()).contains("trace-from-the-checkout-request");
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
    }
}
