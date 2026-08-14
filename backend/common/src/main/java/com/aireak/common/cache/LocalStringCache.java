package com.aireak.common.cache;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, per-JVM (NOT shared across service instances/pods) {@code String -> String} cache
 * backed by Guava's {@link Cache}.
 *
 * <p>Intended as a read-through optimization layer in front of a distributed store (Redis/DB) for
 * idempotency-style lookups whose cached value, once known, never changes. Callers must only
 * {@link #put} immutable/positive facts (e.g. "this key definitely resolved to this id") — never a
 * speculative, in-flight, or absent state. This cache is not shared across instances, so a
 * wrongly-cached negative/in-progress result on one instance would never be corrected by what a
 * different instance later writes to the shared store.
 */
public final class LocalStringCache {

    /**
     * Which clock an entry's {@code expiry} duration is measured against — see
     * {@link #LocalStringCache(long, Duration, ExpiryPolicy)}.
     */
    public enum ExpiryPolicy {
        /**
         * Entry expires a fixed duration after it was written, regardless of how often it's
         * read. Fits a cache whose TTL is deliberately bounding a retry/double-submit window
         * (e.g. "how long might a client plausibly retry the same request") — a property of the
         * key's age, not of how popular it is.
         */
        AFTER_WRITE,
        /**
         * Entry expires a fixed duration after it was LAST read (or written, if never read
         * since). Fits a cache of permanent facts over a small, bounded key space, where the
         * goal is "never re-fetch a key that's still actively in use" — an actively-hit key
         * effectively never expires, while one nobody has asked about in a while is freed.
         * Wrong for the retry-window use case above: it would let a request that's still being
         * retried (each retry resets the timer) hold its entry open indefinitely instead of
         * falling back to the backing store's own TTL.
         */
        AFTER_ACCESS
    }

    private final Cache<String, String> delegate;

    /**
     * Equivalent to {@link #LocalStringCache(long, Duration, ExpiryPolicy)} with
     * {@link ExpiryPolicy#AFTER_WRITE} — the original, still-most-common shape for this cache
     * (bounding a retry window).
     *
     * @param maximumSize      approximate upper bound on entry count (Guava evicts once exceeded) —
     *                         bounds this cache's worst-case heap footprint per JVM.
     * @param expireAfterWrite how long an entry is served after being written, regardless of reads.
     *                         Size this to the realistic retry/double-submit window you want to
     *                         absorb, not to the backing store's own TTL.
     */
    public LocalStringCache(long maximumSize, Duration expireAfterWrite) {
        this(maximumSize, expireAfterWrite, ExpiryPolicy.AFTER_WRITE, Ticker.systemTicker());
    }

    /**
     * As {@link #LocalStringCache(long, Duration)}, but with an injectable {@link Ticker} so tests
     * can advance time deterministically instead of depending on wall-clock sleeps.
     */
    public LocalStringCache(long maximumSize, Duration expireAfterWrite, Ticker ticker) {
        this(maximumSize, expireAfterWrite, ExpiryPolicy.AFTER_WRITE, ticker);
    }

    /**
     * @param maximumSize approximate upper bound on entry count (Guava evicts once exceeded) —
     *                    bounds this cache's worst-case heap footprint per JVM.
     * @param expiry      how long an entry is served, measured per {@code policy}.
     * @param policy      which clock {@code expiry} is measured against — see {@link ExpiryPolicy}.
     */
    public LocalStringCache(long maximumSize, Duration expiry, ExpiryPolicy policy) {
        this(maximumSize, expiry, policy, Ticker.systemTicker());
    }

    /**
     * As {@link #LocalStringCache(long, Duration, ExpiryPolicy)}, but with an injectable
     * {@link Ticker} so tests can advance time deterministically instead of depending on
     * wall-clock sleeps.
     */
    public LocalStringCache(long maximumSize, Duration expiry, ExpiryPolicy policy, Ticker ticker) {
        CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder()
                .maximumSize(maximumSize)
                .ticker(ticker)
                .recordStats();
        if (policy == ExpiryPolicy.AFTER_ACCESS) {
            builder.expireAfterAccess(expiry.toNanos(), TimeUnit.NANOSECONDS);
        } else {
            builder.expireAfterWrite(expiry.toNanos(), TimeUnit.NANOSECONDS);
        }
        this.delegate = builder.build();
    }

    /** Returns the cached value for {@code key}, or empty if absent or expired. */
    public Optional<String> getIfPresent(String key) {
        return Optional.ofNullable(delegate.getIfPresent(key));
    }

    /** Stores/overwrites {@code value} for {@code key}, resetting its expiry. */
    public void put(String key, String value) {
        delegate.put(key, value);
    }

    /** Evicts {@code key} if present; a no-op otherwise. */
    public void invalidate(String key) {
        delegate.invalidate(key);
    }

    /** Approximate current entry count — for tests/metrics; not exact under concurrent writes. */
    public long size() {
        return delegate.size();
    }
}
