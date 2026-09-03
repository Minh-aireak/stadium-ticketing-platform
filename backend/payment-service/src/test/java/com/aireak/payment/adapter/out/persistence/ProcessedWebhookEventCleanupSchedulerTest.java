package com.aireak.payment.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Mirrors {@code ProcessedInventoryEventCleanupSchedulerTest} — same job, different table. */
@ExtendWith(MockitoExtension.class)
class ProcessedWebhookEventCleanupSchedulerTest {

    @Mock
    private ProcessedWebhookEventJpaRepository processedWebhookEventJpaRepository;

    @Test
    void deletesRowsOlderThanConfiguredRetention() {
        var scheduler = new ProcessedWebhookEventCleanupScheduler(processedWebhookEventJpaRepository, 14);
        when(processedWebhookEventJpaRepository.deleteByCreatedAtBefore(any())).thenReturn(3);

        scheduler.cleanup();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(processedWebhookEventJpaRepository).deleteByCreatedAtBefore(cutoffCaptor.capture());
        Instant expectedCutoff = Instant.now().minus(14, ChronoUnit.DAYS);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
    }

    @Test
    void usesConfiguredRetentionDaysNotAHardcodedDefault() {
        var scheduler = new ProcessedWebhookEventCleanupScheduler(processedWebhookEventJpaRepository, 30);
        when(processedWebhookEventJpaRepository.deleteByCreatedAtBefore(any())).thenReturn(0);

        scheduler.cleanup();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(processedWebhookEventJpaRepository).deleteByCreatedAtBefore(cutoffCaptor.capture());
        Instant expectedCutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
        verifyNoMoreInteractions(processedWebhookEventJpaRepository);
    }
}
