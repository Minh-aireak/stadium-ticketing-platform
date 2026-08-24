package com.aireak.catalog.adapter.out.cache;

import com.aireak.catalog.application.port.out.LiveSeatAvailabilityPort;
import com.google.common.base.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The trade being made here is "a hot page reads Redis once a second instead of once a request,
 * except when the count is small enough for a second of staleness to matter". Both halves need a
 * clock that can be wound forward, so the {@link Ticker} is injected the same way
 * {@code LocalStringCache} does it.
 */
@ExtendWith(MockitoExtension.class)
class AdaptiveTtlLiveSeatStoreTest {

    private static final Duration LOCAL_TTL = Duration.ofSeconds(1);
    private static final int SCARCITY_THRESHOLD = 25;

    @Mock
    private LiveSeatAvailabilityPort delegate;

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = new Ticker() {
        @Override
        public long read() {
            return nanos.get();
        }
    };

    private AdaptiveTtlLiveSeatStore store;

    @BeforeEach
    void setUp() {
        store = new AdaptiveTtlLiveSeatStore(delegate, LOCAL_TTL, SCARCITY_THRESHOLD, 1_000, ticker);
    }

    @Test
    void twoReadsWithinTheSameSecondTouchRedisOnce() {
        when(delegate.getAll(List.of("showtime-1"))).thenReturn(Map.of("showtime-1", 300));

        assertThat(store.getAll(List.of("showtime-1"))).containsEntry("showtime-1", 300);
        nanos.addAndGet(Duration.ofMillis(900).toNanos());
        assertThat(store.getAll(List.of("showtime-1"))).containsEntry("showtime-1", 300);

        verify(delegate, times(1)).getAll(anyCollection());
    }

    @Test
    void theEntryExpiresOnceTheTtlHasPassed() {
        when(delegate.getAll(List.of("showtime-1"))).thenReturn(Map.of("showtime-1", 300));
        store.getAll(List.of("showtime-1"));

        nanos.addAndGet(LOCAL_TTL.plusMillis(1).toNanos());
        store.getAll(List.of("showtime-1"));

        verify(delegate, times(2)).getAll(anyCollection());
    }

    @Test
    void aCountBelowTheThresholdIsReReadEveryTime() {
        when(delegate.getAll(List.of("showtime-1")))
                .thenReturn(Map.of("showtime-1", SCARCITY_THRESHOLD - 1));

        for (int i = 0; i < 5; i++) {
            assertThat(store.getAll(List.of("showtime-1")))
                    .containsEntry("showtime-1", SCARCITY_THRESHOLD - 1);
        }

        verify(delegate, times(5)).getAll(anyCollection());
    }

    @Test
    void aCountExactlyAtTheThresholdIsStillCached() {
        when(delegate.getAll(List.of("showtime-1"))).thenReturn(Map.of("showtime-1", SCARCITY_THRESHOLD));

        store.getAll(List.of("showtime-1"));
        store.getAll(List.of("showtime-1"));

        verify(delegate, times(1)).getAll(anyCollection());
    }

    /**
     * A match page mixes plenty-left and nearly-sold-out showtimes, so the cached ones must not
     * drag the scarce ones into a stale answer, nor the scarce ones cost the cached ones a lookup.
     */
    @Test
    void onlyTheUncachedIdsOfAPageAreAskedForOnTheSecondRead() {
        when(delegate.getAll(List.of("plentiful", "scarce")))
                .thenReturn(Map.of("plentiful", 300, "scarce", 4));
        store.getAll(List.of("plentiful", "scarce"));

        when(delegate.getAll(List.of("scarce"))).thenReturn(Map.of("scarce", 4));
        assertThat(store.getAll(List.of("plentiful", "scarce")))
                .containsEntry("plentiful", 300)
                .containsEntry("scarce", 4);

        verify(delegate).getAll(List.of("plentiful", "scarce"));
        verify(delegate).getAll(List.of("scarce"));
        verifyNoMoreInteractions(delegate);
    }

    /**
     * The write side (a Lua decrement in {@code RedisSeatAvailabilityCounter}) has no way to reach
     * this cache except through {@code LocalSeatCountCache#invalidate}, so a pod that just changed a
     * counter must not keep serving the value from before that write.
     */
    @Test
    void invalidateDropsThisPodsCopySoTheNextReadGoesBackToRedis() {
        when(delegate.getAll(List.of("showtime-1"))).thenReturn(Map.of("showtime-1", 300));
        store.getAll(List.of("showtime-1"));

        store.invalidate("showtime-1");
        when(delegate.getAll(List.of("showtime-1"))).thenReturn(Map.of("showtime-1", 250));
        assertThat(store.getAll(List.of("showtime-1"))).containsEntry("showtime-1", 250);

        verify(delegate, times(2)).getAll(anyCollection());
    }

    /**
     * {@code RedisLiveSeatStore} answers an unreachable Redis with an empty map rather than
     * throwing; that has to survive the extra tier, or every browse request turns into a 500 the
     * moment Redis blinks.
     */
    @Test
    void aRedisFailureStillFailsOpenAsAnEmptyMap() {
        when(delegate.getAll(List.of("showtime-1"))).thenReturn(Map.of());

        assertThat(store.getAll(List.of("showtime-1"))).isEmpty();
    }

    @Test
    void anEmptyRequestNeverReachesRedis() {
        assertThat(store.getAll(List.of())).isEmpty();

        verify(delegate, never()).getAll(anyCollection());
    }
}
