package com.aireak.catalog.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatAvailabilityProjectionAdapterTest {

    @Mock
    private MatchJpaRepository matchJpaRepository;
    @Mock
    private ProcessedInventoryEventJpaRepository processedEventRepository;

    private SeatAvailabilityProjectionAdapter adapter;

    private void newAdapter() {
        adapter = new SeatAvailabilityProjectionAdapter(matchJpaRepository, processedEventRepository);
    }

    @Test
    void decrementAvailableSeats_forAnAlreadyProcessedEvent_skipsTheDecrementAndReturnsFalse() {
        newAdapter();
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        boolean applied = adapter.decrementAvailableSeats("evt-1", "showtime-1", 2);

        assertThat(applied).isFalse();
        verify(matchJpaRepository, never()).decrementAvailableSeats(anyString(), anyInt());
    }

    @Test
    void decrementAvailableSeats_forANewEvent_appliesTheDecrementAndMarksProcessed() {
        newAdapter();
        when(processedEventRepository.existsById("evt-2")).thenReturn(false);
        when(matchJpaRepository.decrementAvailableSeats("showtime-1", 2)).thenReturn(1);

        boolean applied = adapter.decrementAvailableSeats("evt-2", "showtime-1", 2);

        assertThat(applied).isTrue();
        verify(matchJpaRepository).decrementAvailableSeats("showtime-1", 2);
        verify(processedEventRepository).save(argThatEventId("evt-2"));
    }

    private static ProcessedInventoryEventJpaEntity argThatEventId(String eventId) {
        return org.mockito.ArgumentMatchers.argThat(entity -> entity.getEventId().equals(eventId));
    }
}
