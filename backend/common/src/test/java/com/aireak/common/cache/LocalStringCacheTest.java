package com.aireak.common.cache;

import com.google.common.base.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LocalStringCacheTest {

    /** Manually-advanced {@link Ticker} so expiry tests don't depend on wall-clock sleeps. */
    private static final class FakeTicker extends Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    @Test
    void getIfPresent_forAnUnknownKey_returnsEmpty() {
        LocalStringCache cache = new LocalStringCache(10, Duration.ofSeconds(30));

        assertThat(cache.getIfPresent("missing")).isEmpty();
    }

    @Test
    void put_thenGetIfPresent_returnsTheStoredValue() {
        LocalStringCache cache = new LocalStringCache(10, Duration.ofSeconds(30));

        cache.put("key", "value");

        assertThat(cache.getIfPresent("key")).contains("value");
    }

    @Test
    void invalidate_removesTheEntry() {
        LocalStringCache cache = new LocalStringCache(10, Duration.ofSeconds(30));
        cache.put("key", "value");

        cache.invalidate("key");

        assertThat(cache.getIfPresent("key")).isEmpty();
    }

    @Test
    void invalidate_forAnUnknownKey_isANoOp() {
        LocalStringCache cache = new LocalStringCache(10, Duration.ofSeconds(30));

        cache.invalidate("missing");

        assertThat(cache.getIfPresent("missing")).isEmpty();
    }

    @Test
    void entry_isStillPresentBeforeExpireAfterWriteElapses() {
        FakeTicker ticker = new FakeTicker();
        LocalStringCache cache = new LocalStringCache(10, Duration.ofSeconds(30), ticker);
        cache.put("key", "value");

        ticker.advance(Duration.ofSeconds(29));

        assertThat(cache.getIfPresent("key")).contains("value");
    }

    @Test
    void entry_expiresOnceExpireAfterWriteElapses() {
        FakeTicker ticker = new FakeTicker();
        LocalStringCache cache = new LocalStringCache(10, Duration.ofSeconds(30), ticker);
        cache.put("key", "value");

        ticker.advance(Duration.ofSeconds(31));

        assertThat(cache.getIfPresent("key")).isEmpty();
    }

    @Test
    void maximumSize_evictsOnceExceeded() {
        LocalStringCache cache = new LocalStringCache(2, Duration.ofMinutes(5));

        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        assertThat(cache.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void afterAccessPolicy_keepsAnEntryAliveAsLongAsItKeepsBeingRead() {
        FakeTicker ticker = new FakeTicker();
        LocalStringCache cache = new LocalStringCache(10, Duration.ofSeconds(30),
                LocalStringCache.ExpiryPolicy.AFTER_ACCESS, ticker);
        cache.put("key", "value");

        // Read the entry just before every expiry window elapses — each read must reset the
        // clock, so the entry survives well past what its expiry duration alone would allow.
        for (int i = 0; i < 5; i++) {
            ticker.advance(Duration.ofSeconds(29));
            assertThat(cache.getIfPresent("key")).contains("value");
        }
    }

    @Test
    void afterAccessPolicy_expiresOnceNobodyReadsItForTheFullDuration() {
        FakeTicker ticker = new FakeTicker();
        LocalStringCache cache = new LocalStringCache(10, Duration.ofSeconds(30),
                LocalStringCache.ExpiryPolicy.AFTER_ACCESS, ticker);
        cache.put("key", "value");

        ticker.advance(Duration.ofSeconds(31));

        assertThat(cache.getIfPresent("key")).isEmpty();
    }

    @Test
    void afterWritePolicy_expiresEvenIfReadRepeatedly() {
        FakeTicker ticker = new FakeTicker();
        LocalStringCache cache = new LocalStringCache(10, Duration.ofSeconds(30),
                LocalStringCache.ExpiryPolicy.AFTER_WRITE, ticker);
        cache.put("key", "value");

        // Unlike AFTER_ACCESS, repeated reads must NOT reset the clock — the entry still expires
        // once 30s have passed since it was written, regardless of how often it was read.
        ticker.advance(Duration.ofSeconds(20));
        assertThat(cache.getIfPresent("key")).contains("value");
        ticker.advance(Duration.ofSeconds(20));

        assertThat(cache.getIfPresent("key")).isEmpty();
    }
}
