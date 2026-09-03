package com.aireak.catalog.adapter.out.persistence;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Purges {@code processed_inventory_events} rows older than {@code retentionDays}.
 *
 * <p>{@link SeatAvailabilityProjectionAdapter} writes one row per consumed {@code SeatsSoldEvent}
 * to make the {@code available_seats} decrement idempotent, and nothing has ever deleted them — so
 * the table grew by a row for every seat sold on the platform, forever. Nothing reads a row that
 * old either: the adapter only asks whether an id exists, and a Kafka redelivery arriving days
 * after the original is not a case worth keeping a row for.
 *
 * <p>Mirrors notification-service's {@code ProcessedEventCleanupScheduler} down to the property
 * names. There is a third, payment-service's {@code processed_webhook_events}, purged by its own
 * {@code ProcessedWebhookEventCleanupScheduler}; this javadoc used to say these two were the only
 * ones, which is part of how that table went unpurged. Every other service uses a Redisson store
 * whose keys expire on their own.
 */
@Slf4j
@Component
public class ProcessedInventoryEventCleanupScheduler {

    private final ProcessedInventoryEventJpaRepository processedInventoryEventJpaRepository;
    private final int retentionDays;

    ProcessedInventoryEventCleanupScheduler(
            ProcessedInventoryEventJpaRepository processedInventoryEventJpaRepository,
            @Value("${processed-events.cleanup.retention-days:14}") int retentionDays) {
        this.processedInventoryEventJpaRepository = processedInventoryEventJpaRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${processed-events.cleanup.fixed-delay-ms:86400000}")
    @SchedulerLock(name = "catalog-processedInventoryEventCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = processedInventoryEventJpaRepository.deleteByProcessedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} processed_inventory_events row(s) older than {} day(s)", deleted, retentionDays);
        }
    }
}
