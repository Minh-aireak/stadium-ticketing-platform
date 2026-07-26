package com.aireak.booking.adapter.out.persistence;

import com.aireak.booking.application.port.out.BookingRepository;
import com.aireak.booking.domain.model.Booking;
import com.aireak.booking.domain.model.BookingAmount;
import com.aireak.booking.domain.model.SeatSelection;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Docker-free companion to {@code OutboxEventPublisherIntegrationTest}: verifies specifically
 * that {@link BookingJpaEntity} — after having its own shadowing {@code createdAt} field removed
 * in favor of the inherited {@code @CreatedDate}/{@code @LastModifiedDate} fields on
 * {@code BaseAuditEntity} — still gets {@code created_at} populated on insert and preserved
 * (not nulled) across a later update.
 *
 * <p>This only exercises generic Hibernate/Spring Data JPA auditing behavior
 * ({@code AuditingEntityListener}, {@code @Version}-based new-vs-existing detection,
 * {@code updatable = false} column exclusion from UPDATE), which is dialect-independent — H2 is
 * sufficient here. It does NOT replace {@code OutboxEventPublisherIntegrationTest}'s coverage of
 * the real Postgres schema (via Flyway) and the outbox pattern; run that one too wherever Docker
 * is available before relying on this fix in production.
 *
 * <p>Hibernate schema management is disabled ({@code ddl-auto=none}) and {@code schema.sql}
 * (test resources) creates just the {@code bookings} table by hand instead: {@code @DataJpaTest}
 * would otherwise also register {@code OutboxEventEntity} (same package tree), whose {@code
 * payload} column is hard-coded to the Postgres-only {@code jsonb} type and can't be
 * auto-generated against H2.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect="
})
@Import({BookingJpaAuditingH2Test.AuditingConfig.class, BookingPersistenceAdapter.class})
class BookingJpaAuditingH2Test {

    @TestConfiguration
    @EnableJpaAuditing
    static class AuditingConfig {
    }

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void createdAtIsPopulatedOnInsertAndPreservedAcrossAnUpdate() {
        Booking booking = Booking.create(
                "customer-1", "showtime-1",
                new SeatSelection(List.of("A1", "A2")),
                BookingAmount.of(new BigDecimal("150.00"), "USD"), null);
        String bookingId = booking.getBookingId();

        bookingRepository.save(booking);
        entityManager.flush();
        entityManager.clear();

        Booking reloaded = bookingRepository.findById(bookingId).orElseThrow();
        Instant createdAtAfterInsert = reloaded.getCreatedAt();
        assertThat(createdAtAfterInsert).isNotNull();

        reloaded.markPendingPayment();
        bookingRepository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        Booking reloadedAfterUpdate = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(reloadedAfterUpdate.getCreatedAt()).isEqualTo(createdAtAfterInsert);
    }
}
