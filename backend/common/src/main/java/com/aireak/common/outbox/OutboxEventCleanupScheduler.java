package com.aireak.common.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Debezium reads {@code outbox_events} via Postgres logical replication (WAL), not by querying or
 * deleting rows directly — once a row has reached the WAL, its continued presence in the table is
 * irrelevant to CDC delivery. This job purges rows older than {@code retentionDays} so the table
 * doesn't grow unbounded.
 *
 * <p>No {@code @Component}: notification-service also component-scans this module but has no
 * outbox table, so each publishing service registers this as an explicit {@code @Bean} instead.
 *
 * <p>The ShedLock name resolves from {@code spring.application.name}, which every service already
 * sets, rather than being hard-coded per copy. Only one instance of a given service runs the purge
 * per interval; a delete of already-expired rows is idempotent, so even the brief window during a
 * rename where two names coexist is harmless.
 */
public class OutboxEventCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventCleanupScheduler.class);

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final int retentionDays;

    public OutboxEventCleanupScheduler(
            OutboxEventJpaRepository outboxEventJpaRepository,
            @Value("${outbox.cleanup.retention-days:14}") int retentionDays) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${outbox.cleanup.fixed-delay-ms:86400000}")
    @SchedulerLock(name = "${spring.application.name}-outboxCleanup",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = outboxEventJpaRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} outbox_events row(s) older than {} day(s)", deleted, retentionDays);
        }
    }
}
