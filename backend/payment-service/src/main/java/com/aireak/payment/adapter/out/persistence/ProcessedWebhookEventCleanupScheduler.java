package com.aireak.payment.adapter.out.persistence;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Purges {@code processed_webhook_events} rows older than {@code retentionDays}.
 *
 * <p>{@code StripeWebhookService} writes one row per delivered Stripe event so a retried delivery
 * is a no-op, and nothing has ever deleted them — the table grew by a row for every webhook the
 * platform has ever received, forever. Nothing reads a row that old either: the guard only asks
 * whether an id exists, and Stripe abandons a delivery after about three days of retries, so a row
 * two weeks old can no longer be matched by anything Stripe will still send.
 *
 * <p>Mirrors notification-service's {@code ProcessedEventCleanupScheduler} and
 * match-catalog-service's {@code ProcessedInventoryEventCleanupScheduler} down to the property
 * names — the javadoc on both of those used to call themselves the only two Postgres-backed
 * idempotency stores on the platform, which is how this third one went without a purge.
 */
@Slf4j
@Component
public class ProcessedWebhookEventCleanupScheduler {

    private final ProcessedWebhookEventJpaRepository processedWebhookEventJpaRepository;
    private final int retentionDays;

    ProcessedWebhookEventCleanupScheduler(
            ProcessedWebhookEventJpaRepository processedWebhookEventJpaRepository,
            @Value("${processed-events.cleanup.retention-days:14}") int retentionDays) {
        this.processedWebhookEventJpaRepository = processedWebhookEventJpaRepository;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${processed-events.cleanup.fixed-delay-ms:86400000}")
    @SchedulerLock(name = "payment-processedWebhookEventCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = processedWebhookEventJpaRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Purged {} processed_webhook_events row(s) older than {} day(s)", deleted, retentionDays);
        }
    }
}
