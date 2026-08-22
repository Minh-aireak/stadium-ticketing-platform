package com.aireak.common.outbox;

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

@ExtendWith(MockitoExtension.class)
class OutboxEventCleanupSchedulerTest {

    @Mock
    private OutboxEventJpaRepository outboxEventJpaRepository;

    @Test
    void deletesRowsOlderThanConfiguredRetention() {
        OutboxEventCleanupScheduler scheduler = new OutboxEventCleanupScheduler(outboxEventJpaRepository, 14);
        when(outboxEventJpaRepository.deleteByCreatedAtBefore(any())).thenReturn(3);

        scheduler.cleanup();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(outboxEventJpaRepository).deleteByCreatedAtBefore(cutoffCaptor.capture());
        Instant expectedCutoff = Instant.now().minus(14, ChronoUnit.DAYS);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
    }

    @Test
    void usesConfiguredRetentionDaysNotAHardcodedDefault() {
        OutboxEventCleanupScheduler scheduler = new OutboxEventCleanupScheduler(outboxEventJpaRepository, 30);
        when(outboxEventJpaRepository.deleteByCreatedAtBefore(any())).thenReturn(0);

        scheduler.cleanup();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(outboxEventJpaRepository).deleteByCreatedAtBefore(cutoffCaptor.capture());
        Instant expectedCutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
        verifyNoMoreInteractions(outboxEventJpaRepository);
    }
}
