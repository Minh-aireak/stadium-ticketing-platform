package com.aireak.catalog.adapter.out.cache;

/**
 * The per-pod read cache's invalidation hook, narrowed to the one operation the write side needs.
 *
 * <p>{@link RedisSeatAvailabilityCounter} depends on this rather than on
 * {@link AdaptiveTtlLiveSeatStore} itself: the writer's interest is "the value I just changed must
 * not be served from memory a moment longer", not the caching strategy behind it. Only this pod's
 * copy can be dropped — every other pod's staleness stays bounded by its own local TTL, which is
 * the trade {@link AdaptiveTtlLiveSeatStore} already documents.
 */
interface LocalSeatCountCache {

    void invalidate(String showtimeId);
}
