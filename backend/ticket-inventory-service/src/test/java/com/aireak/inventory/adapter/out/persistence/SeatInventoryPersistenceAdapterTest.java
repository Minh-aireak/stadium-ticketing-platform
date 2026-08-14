package com.aireak.inventory.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the local-cache layer {@link SeatInventoryPersistenceAdapter#existsByShowtimeId}
 * adds in front of Postgres — the hottest existence check in the platform (runs on every
 * reserve/hold call, inside the per-showtime Redisson lock).
 */
@ExtendWith(MockitoExtension.class)
class SeatInventoryPersistenceAdapterTest {

    @Mock
    private SeatInventoryJpaRepository jpaRepository;
    @Mock
    private SeatJpaRepository seatJpaRepository;

    private SeatInventoryPersistenceAdapter adapter;

    private void newAdapter() {
        adapter = new SeatInventoryPersistenceAdapter(jpaRepository, seatJpaRepository);
    }

    @Test
    void existsByShowtimeId_forAnUnknownShowtime_queriesPostgresAndReturnsFalse() {
        newAdapter();
        when(jpaRepository.existsByShowtimeId("unknown")).thenReturn(false);

        boolean exists = adapter.existsByShowtimeId("unknown");

        assertThat(exists).isFalse();
    }

    @Test
    void existsByShowtimeId_forAnUnknownShowtime_neverCachesTheNegativeResult() {
        newAdapter();
        when(jpaRepository.existsByShowtimeId("unknown")).thenReturn(false);

        adapter.existsByShowtimeId("unknown");
        adapter.existsByShowtimeId("unknown");

        // A "false" result must never be cached — it's the exact mutable state (inventory not
        // generated yet) this check exists to keep re-verifying. Every call re-hits Postgres.
        verify(jpaRepository, times(2)).existsByShowtimeId("unknown");
    }

    @Test
    void existsByShowtimeId_forAKnownShowtime_answersSubsequentCallsFromTheLocalCacheWithoutHittingPostgres() {
        newAdapter();
        when(jpaRepository.existsByShowtimeId("showtime-1")).thenReturn(true);

        boolean first = adapter.existsByShowtimeId("showtime-1");
        boolean second = adapter.existsByShowtimeId("showtime-1");
        boolean third = adapter.existsByShowtimeId("showtime-1");

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isTrue();
        verify(jpaRepository, times(1)).existsByShowtimeId("showtime-1");
    }

    @Test
    void existsByShowtimeId_cachesPerShowtimeIndependently() {
        newAdapter();
        when(jpaRepository.existsByShowtimeId("showtime-a")).thenReturn(true);
        when(jpaRepository.existsByShowtimeId("showtime-b")).thenReturn(false);

        assertThat(adapter.existsByShowtimeId("showtime-a")).isTrue();
        assertThat(adapter.existsByShowtimeId("showtime-b")).isFalse();
        // showtime-a stays cached; showtime-b (never cached) re-hits Postgres every time.
        adapter.existsByShowtimeId("showtime-a");
        adapter.existsByShowtimeId("showtime-b");

        verify(jpaRepository, times(1)).existsByShowtimeId("showtime-a");
        verify(jpaRepository, times(2)).existsByShowtimeId("showtime-b");
        verify(jpaRepository, never()).existsByShowtimeId("unrelated");
    }
}
