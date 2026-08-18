package com.aireak.catalog.adapter.out.persistence;

import com.aireak.catalog.application.port.out.LiveSeatAvailabilityPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

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
    @Mock
    private LiveSeatAvailabilityPort liveSeatAvailabilityPort;

    private SeatAvailabilityProjectionAdapter adapter;

    private void newAdapter() {
        adapter = new SeatAvailabilityProjectionAdapter(
                matchJpaRepository, processedEventRepository, liveSeatAvailabilityPort);
    }

    @Test
    void decrementAvailableSeats_forAnAlreadyProcessedEvent_skipsTheDecrementAndReturnsFalse() {
        newAdapter();
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        boolean applied = adapter.decrementAvailableSeats("evt-1", "showtime-1", 2);

        assertThat(applied).isFalse();
        verify(matchJpaRepository, never()).decrementAvailableSeats(anyString(), anyInt());
        verify(liveSeatAvailabilityPort, never()).publish(anyString(), anyInt());
    }

    @Test
    void decrementAvailableSeats_forANewEvent_appliesTheDecrementAndMarksProcessed() {
        newAdapter();
        when(processedEventRepository.existsById("evt-2")).thenReturn(false);
        when(matchJpaRepository.decrementAvailableSeats("showtime-1", 2)).thenReturn(1);
        when(matchJpaRepository.findAvailableSeats("showtime-1")).thenReturn(Optional.of(8));

        boolean applied = adapter.decrementAvailableSeats("evt-2", "showtime-1", 2);

        assertThat(applied).isTrue();
        verify(matchJpaRepository).decrementAvailableSeats("showtime-1", 2);
        verify(processedEventRepository).save(argThatEventId("evt-2"));
        verify(liveSeatAvailabilityPort).publish("showtime-1", 8);
    }

    @Test
    void decrementAvailableSeats_whenPublishingLiveSeatCountFails_stillReturnsTrue() {
        newAdapter();
        when(processedEventRepository.existsById("evt-3")).thenReturn(false);
        when(matchJpaRepository.decrementAvailableSeats("showtime-1", 2)).thenReturn(1);
        when(matchJpaRepository.findAvailableSeats("showtime-1")).thenThrow(new RuntimeException("redis down"));

        boolean applied = adapter.decrementAvailableSeats("evt-3", "showtime-1", 2);

        assertThat(applied).isTrue();
        verify(processedEventRepository).save(argThatEventId("evt-3"));
    }

    private static ProcessedInventoryEventJpaEntity argThatEventId(String eventId) {
        return org.mockito.ArgumentMatchers.argThat(entity -> entity.getEventId().equals(eventId));
    }
}
