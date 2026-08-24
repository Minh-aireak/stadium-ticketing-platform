package com.aireak.catalog.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This adapter is the durable half of the projection and nothing else — the Redis counter it used
 * to write through to now belongs to {@code RedisSeatAvailabilityCounter}, so the only behavior
 * left to pin down here is "apply once, and never twice".
 */
@ExtendWith(MockitoExtension.class)
class SeatAvailabilityProjectionAdapterTest {

    @Mock
    private MatchJpaRepository matchJpaRepository;
    @Mock
    private ProcessedInventoryEventJpaRepository processedEventRepository;

    private SeatAvailabilityProjectionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SeatAvailabilityProjectionAdapter(matchJpaRepository, processedEventRepository);
    }

    @Test
    void decrementAvailableSeats_forAnAlreadyProcessedEvent_skipsTheDecrementAndReturnsFalse() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        boolean applied = adapter.decrementAvailableSeats("evt-1", "showtime-1", 2);

        assertThat(applied).isFalse();
        verify(matchJpaRepository, never()).decrementAvailableSeats(anyString(), anyInt());
    }

    @Test
    void decrementAvailableSeats_forANewEvent_appliesTheDecrementAndMarksProcessed() {
        when(processedEventRepository.existsById("evt-2")).thenReturn(false);
        when(matchJpaRepository.decrementAvailableSeats("showtime-1", 2)).thenReturn(1);

        boolean applied = adapter.decrementAvailableSeats("evt-2", "showtime-1", 2);

        assertThat(applied).isTrue();
        verify(matchJpaRepository).decrementAvailableSeats("showtime-1", 2);
        verify(processedEventRepository).save(argThatEventId("evt-2"));
    }

    /**
     * An event naming a showtime that no longer exists must not be recorded as processed: swallowing
     * it would make the failure permanent, whereas throwing lets Kafka redeliver.
     */
    @Test
    void decrementAvailableSeats_forAnUnknownShowtime_throwsAndRecordsNothing() {
        when(processedEventRepository.existsById("evt-3")).thenReturn(false);
        when(matchJpaRepository.decrementAvailableSeats("missing", 2)).thenReturn(0);

        assertThatThrownBy(() -> adapter.decrementAvailableSeats("evt-3", "missing", 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");

        verify(processedEventRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findAvailableSeats_readsTheCommittedCountStraightFromPostgres() {
        when(matchJpaRepository.findAvailableSeats("showtime-1")).thenReturn(Optional.of(8));

        assertThat(adapter.findAvailableSeats("showtime-1")).contains(8);
    }

    private static ProcessedInventoryEventJpaEntity argThatEventId(String eventId) {
        return org.mockito.ArgumentMatchers.argThat(entity -> entity.getEventId().equals(eventId));
    }
}
