package com.aireak.catalog.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

interface ProcessedInventoryEventJpaRepository
        extends JpaRepository<ProcessedInventoryEventJpaEntity, String> {

    /**
     * Bulk-purges rows older than {@code cutoff}, for {@link ProcessedInventoryEventCleanupScheduler}.
     * A derived {@code deleteBy...} would load every matching row as an entity first; this issues a
     * single DELETE, which matters because the table is written to once per sold-seat event and is
     * never read except by the {@code existsById} idempotency check.
     */
    @Modifying
    @Query("delete from ProcessedInventoryEventJpaEntity e where e.processedAt < :cutoff")
    int deleteByProcessedAtBefore(Instant cutoff);
}
