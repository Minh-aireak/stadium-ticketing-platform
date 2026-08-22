package com.aireak.booking.adapter.out.persistence.outbox;

import com.aireak.common.outbox.OutboxEventEntity;
import com.aireak.common.outbox.OutboxEventJpaRepository;
import com.aireak.booking.adapter.out.persistence.BookingPersistenceAdapter;
import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.config.InfraConfig;
import com.aireak.booking.application.port.out.DomainEventPublisher;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.BookingStatus;
import com.aireak.booking.domain.model.SeatSelection;
import tools.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;
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
 * Verifies the core outbox guarantee: writing the Booking aggregate and its
 * outbox row happen in the same DB transaction. Runs against a real Postgres
 * (via Testcontainers) with Flyway migrations applied — Debezium/Kafka are
 * out of scope here (see booking-service/infra/debezium/README.md for the
 * manual end-to-end CDC check).
 *
 * <p>{@code @DataJpaTest}'s slice only imports {@code DataJpaRepositoriesAutoConfiguration}
 * and {@code HibernateJpaAutoConfiguration} — Flyway is NOT part of it, so migrations are
 * run explicitly in {@link #migrateSchema()} before the Spring context (and Hibernate's
 * {@code ddl-auto: validate}) starts; otherwise schema validation fails with
 * "missing table [bookings]" against the empty Testcontainers Postgres.
 *
 * <p>This drives {@link BookingRepository#save} and {@link DomainEventPublisher#publishAll}
 * directly rather than through {@code BookingOrchestrationService}. It also covers the
 * multi-save-per-aggregate path (see {@link #updatingAnAlreadyPersistedBookingUpdatesInPlace()}):
 * {@code BookingPersistenceAdapter} carries the JPA {@code @Version} value through the domain
 * object on every load/save round-trip, so a second {@code save()} for the same bookingId is
 * correctly detected as an update ({@code merge()}) rather than a second insert
 * ({@code persist()}) — see the javadoc on {@code Booking.version} for why that distinction
 * matters.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        InfraConfig.class, // @EnableJpaAuditing — without it, BaseAuditEntity's updatedAt is
                            // never populated and every insert fails a NOT NULL constraint.
        BookingPersistenceAdapter.class,
        OutboxConfig.class, // @EntityScan/@EnableJpaRepositories for com.aireak.common.outbox
        OutboxEventPublisher.class,
        OutboxEventPublisherIntegrationTest.TestSupportConfig.class
})
class OutboxEventPublisherIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("booking_db")
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
    private BookingRepository bookingRepository;

    @Autowired
    private DomainEventPublisher eventPublisher;

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void updatingAnAlreadyPersistedBookingUpdatesInPlace() {
        Booking booking = Booking.create(
                "customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(List.of("A1", "A2")),
                BookingAmount.of(new BigDecimal("150.00"), "USD"), null);
        String bookingId = booking.getBookingId();

        bookingRepository.save(booking);
        // Flush + clear the persistence context so the next findById() issues a real
        // SELECT against a fresh session — the same shape as production, where each
        // saga step runs in its own REQUIRES_NEW transaction (see BookingSagaSteps).
        entityManager.flush();
        entityManager.clear();

        Booking reloaded = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(0L);

        reloaded.markPendingPayment();
        bookingRepository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        Booking reloadedAgain = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(reloadedAgain.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(reloadedAgain.getVersion()).isEqualTo(1L);
    }

    @Test
    void cancellingBookingWritesOutboxRowInSameTransaction() {
        Booking booking = Booking.create(
                "customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(List.of("A1", "A2")),
                BookingAmount.of(new BigDecimal("150.00"), "USD"), null);
        booking.cancel("Seat reservation failed: inventory unavailable");

        bookingRepository.save(booking);
        eventPublisher.publishAll(booking.pullDomainEvents());

        List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

        assertThat(rows).hasSize(1);
        OutboxEventEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("booking.booking.cancelled");
        assertThat(row.getAggregateId()).isEqualTo(booking.getBookingId());
        assertThat(row.getEventType()).isEqualTo("BookingCancelledEvent");
        assertThat(row.getPayload()).contains("BookingCancelledEvent", booking.getBookingId());
    }

    /**
     * The durability guarantee that replaced the synchronous refund call: cancelling a paid
     * booking writes the refund request to the outbox in the same transaction as the cancellation
     * itself. Either both land or neither does — where the old HTTP call could fail on its own,
     * silently, after the cancellation had already committed.
     */
    @Test
    void cancellingAPaidBookingWritesTheRefundRequestInTheSameTransaction() {
        Booking booking = Booking.create(
                "customer-1", "customer-1@example.com", "showtime-1",
                new SeatSelection(List.of("A1", "A2")),
                BookingAmount.of(new BigDecimal("150.00"), "USD"), null);
        booking.markPendingPayment();
        booking.confirm();
        booking.pullDomainEvents(); // discard BookingConfirmedEvent, not under test here

        booking.cancelDueToMatchCancellation("Match cancelled by organizer");
        bookingRepository.save(booking);
        eventPublisher.publishAll(booking.pullDomainEvents());

        List<OutboxEventEntity> rows = outboxEventJpaRepository.findAll();

        assertThat(rows).hasSize(2);
        OutboxEventEntity refundRow = rows.stream()
                .filter(r -> "booking.refund.requested".equals(r.getAggregateType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no refund request row written"));
        assertThat(refundRow.getAggregateId()).isEqualTo(booking.getBookingId());
        assertThat(refundRow.getEventType()).isEqualTo("RefundRequestedEvent");
        assertThat(refundRow.getPayload())
                .contains("RefundRequestedEvent", booking.getBookingId(), "Match cancelled by organizer");
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
