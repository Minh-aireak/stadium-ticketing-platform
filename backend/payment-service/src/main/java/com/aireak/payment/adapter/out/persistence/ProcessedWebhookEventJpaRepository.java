package com.aireak.payment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

/**
 * Spring Data JPA repository for processed webhook event persistence.
 * Internal to the adapter layer — not exposed to application or domain.
 */
interface ProcessedWebhookEventJpaRepository extends JpaRepository<ProcessedWebhookEventJpaEntity, String> {

    /**
     * Bulk-purges rows older than {@code cutoff}, for {@link ProcessedWebhookEventCleanupScheduler}.
     * A derived {@code deleteBy...} would load every matching row as an entity first; this issues a
     * single DELETE, which matters because the table is written to once per webhook delivery and is
     * never read except by the {@code existsById} idempotency check.
     */
    @Modifying
    @Query("delete from ProcessedWebhookEventJpaEntity e where e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(Instant cutoff);
}
