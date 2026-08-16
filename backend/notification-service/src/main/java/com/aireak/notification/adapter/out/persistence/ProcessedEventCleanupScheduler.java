package com.aireak.notification.adapter.out.persistence;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Purges {@code processed_events} rows older than {@code retentionDays}.
 *
 * <p>notification-service is the only service that does consumer idempotency in Postgres rather
 * than in Redis — every other one uses a Redisson store whose keys expire on their own. Without
 * this job the table grows by one row per consumed event, forever, and nothing ever reads a row
 * that old: {@code NotificationDispatchService} only asks whether an id exists, and a redelivery
 * arriving weeks after the original is not a case worth guarding against.
 *
 * <p>Mirrors the {@code OutboxEventCleanupScheduler} every producing service already runs, down
 * to the property names.
 */
@Slf4j
@Component
public class ProcessedEventCleanupScheduler {

    private final ProcessedEventJpaRepository processedEventJpaRepository;
    private final int retentionDays;

    public ProcessedEventCleanupScheduler(
            ProcessedEventJpaRepository processedEventJpaRepository,
            @Value("${processed-events.cleanup.retention-days:14}") int retentionDays) {
        this.processedEventJpaRepository = processedEventJpaRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${processed-events.cleanup.fixed-delay-ms:86400000}")
    @SchedulerLock(name = "notification-processedEventCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = processedEventJpaRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} processed_events row(s) older than {} day(s)", deleted, retentionDays);
        }
    }
}
