package com.aireak.inventory.adapter.out.persistence.outbox;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Debezium reads {@code outbox_events} via Postgres logical replication (WAL), not by querying
 * or deleting rows directly — once a row has reached the WAL, its continued presence in the
 * table is irrelevant to CDC delivery. This job purges rows older than {@code retentionDays} so
 * the table doesn't grow unbounded.
 */
@Slf4j
@Component
public class OutboxEventCleanupScheduler {

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final int retentionDays;

    public OutboxEventCleanupScheduler(
            OutboxEventJpaRepository outboxEventJpaRepository,
            @Value("${outbox.cleanup.retention-days:14}") int retentionDays) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${outbox.cleanup.fixed-delay-ms:86400000}")
    @SchedulerLock(name = "inventory-outboxCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = outboxEventJpaRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} outbox_events row(s) older than {} day(s)", deleted, retentionDays);
        }
    }
}
