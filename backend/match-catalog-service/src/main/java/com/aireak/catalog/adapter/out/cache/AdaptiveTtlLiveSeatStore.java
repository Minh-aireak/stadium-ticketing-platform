package com.aireak.catalog.adapter.out.cache;

import com.aireak.catalog.application.port.out.LiveSeatAvailabilityPort;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Per-JVM read cache in front of {@link RedisLiveSeatStore}, with a TTL that depends on how many
 * seats are left.
 *
 * <p><b>Plenty of seats left:</b> served from this pod's memory for {@code localTtl} (1s by
 * default). A hot match page being refreshed thousands of times a second collapses into one Redis
 * MGET per second per pod, and a viewer sees a seat count at most a second old — invisible next to
 * the time it takes to read the page.
 *
 * <p><b>Nearly sold out:</b> a count below {@code scarcityThreshold} is never cached, so every
 * read goes to Redis. Accuracy only really matters once a "3 seats left" is about to become "sold
 * out", and by then the traffic that made caching worth it has already collapsed with the
 * remaining inventory.
 *
 * <p>Deliberately NOT {@code common}'s {@code LocalStringCache}: its contract is that callers only
 * ever store immutable facts, precisely because a wrong entry on one pod is never corrected by
 * what another pod later writes. A seat count is the opposite kind of value — it changes
 * constantly and out of band — so it gets its own cache rather than eroding the guarantee
 * {@code RedissonIdempotencyStore} leans on.
 *
 * <p>Fail-open is preserved end to end: {@link RedisLiveSeatStore#getAll} already swallows Redis
 * errors and returns an empty map, and nothing here turns that into an exception — an id neither
 * tier can resolve is simply absent from the result, and the caller keeps the seat count the Match
 * already carries.
 *
 * <p>Also the {@link LocalSeatCountCache} the write side invalidates through: when
 * {@link RedisSeatAvailabilityCounter} changes a counter, this pod drops its copy immediately
 * instead of serving the pre-write value for the rest of the local TTL. That is a courtesy to the
 * pod that happened to do the write, not a guarantee — no pod can reach another's memory, which is
 * what the TTL bounds instead.
 */
@Slf4j
@Component
@Primary
public class AdaptiveTtlLiveSeatStore implements LiveSeatAvailabilityPort, LocalSeatCountCache {

    private final LiveSeatAvailabilityPort delegate;
    private final Cache<String, Integer> localCounts;
    private final int scarcityThreshold;

    @Autowired
    public AdaptiveTtlLiveSeatStore(
            RedisLiveSeatStore delegate,
            @Value("${catalog.live-seats.local-ttl-millis:1000}") long localTtlMillis,
            // Absolute rather than a percentage of totalSeats: this port speaks showtimeId ->
            // count and nothing else, and widening it just to carry a capacity would push the
            // decision into every caller. 25 is roughly 5% of the stadiums in StadiumCatalog
            // (320-540 seats).
            @Value("${catalog.live-seats.scarcity-threshold:25}") int scarcityThreshold,
            @Value("${catalog.live-seats.max-cached-showtimes:10000}") long maxCachedShowtimes) {
        this(delegate, Duration.ofMillis(localTtlMillis), scarcityThreshold, maxCachedShowtimes,
                Ticker.systemTicker());
    }

    /** As above, but with an injectable {@link Ticker} so tests advance time instead of sleeping. */
    AdaptiveTtlLiveSeatStore(LiveSeatAvailabilityPort delegate, Duration localTtl, int scarcityThreshold,
                             long maxCachedShowtimes, Ticker ticker) {
        this.delegate = delegate;
        this.scarcityThreshold = scarcityThreshold;
        this.localCounts = CacheBuilder.newBuilder()
                .maximumSize(maxCachedShowtimes)
                .expireAfterWrite(localTtl.toNanos(), TimeUnit.NANOSECONDS)
                .ticker(ticker)
                .build();
    }

    /** Only this pod's copy — see the class javadoc for what bounds every other pod's staleness. */
    @Override
    public void invalidate(String showtimeId) {
        localCounts.invalidate(showtimeId);
    }

    /**
     * Serves what this pod already knows and asks Redis for the rest in the delegate's single
     * MGET, so the round-trip count per page stays at most one either way.
     */
    @Override
    public Map<String, Integer> getAll(Collection<String> showtimeIds) {
        if (showtimeIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> resolved = HashMap.newHashMap(showtimeIds.size());
        List<String> misses = new ArrayList<>();
        for (String showtimeId : showtimeIds) {
            // Anything present here is already known to be above the threshold — scarce counts
            // are never written below, so no second check is needed on the read.
            Integer cached = localCounts.getIfPresent(showtimeId);
            if (cached != null) {
                resolved.put(showtimeId, cached);
            } else {
                misses.add(showtimeId);
            }
        }
        if (misses.isEmpty()) {
            return resolved;
        }

        delegate.getAll(misses).forEach((showtimeId, availableSeats) -> {
            resolved.put(showtimeId, availableSeats);
            if (availableSeats >= scarcityThreshold) {
                localCounts.put(showtimeId, availableSeats);
            }
        });
        return resolved;
    }
}
