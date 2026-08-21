package com.aireak.catalog.adapter.out.cache;

import com.aireak.catalog.application.port.out.LiveSeatAvailabilityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Redisson-backed {@link LiveSeatAvailabilityPort}. Written by
 * {@code SeatAvailabilityProjectionAdapter} right after every confirmed seat-sale projection, so
 * reads never wait on a TTL to catch up with the latest count. The TTL here is only a safety net
 * for a value that stops being refreshed (e.g. a showtime that never sells another ticket) — under
 * normal operation this key is kept current by writes, not by expiry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLiveSeatStore implements LiveSeatAvailabilityPort {

    private static final String KEY_PREFIX = "catalog:seats:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final RedissonClient redissonClient;

    @Override
    public void publish(String showtimeId, int availableSeats) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + showtimeId);
        bucket.set(String.valueOf(availableSeats), TTL);
    }

    /**
     * One MGET for the whole batch. Fails open (empty map) on any Redis error — callers fall back
     * to the seat counts they already have.
     */
    @Override
    public Map<String, Integer> getAll(Collection<String> showtimeIds) {
        if (showtimeIds.isEmpty()) {
            return Map.of();
        }
        String[] keys = showtimeIds.stream().distinct().map(id -> KEY_PREFIX + id).toArray(String[]::new);
        try {
            Map<String, String> raw = redissonClient.getBuckets().get(keys);
            Map<String, Integer> counts = HashMap.newHashMap(raw.size());
            raw.forEach((key, value) -> parseSeatCount(key, value)
                    .ifPresent(seats -> counts.put(key.substring(KEY_PREFIX.length()), seats)));
            return counts;
        } catch (Exception e) {
            log.warn("Redis read failed for live seat counts, falling back to cached/DB values: count={}, error={}",
                    keys.length, e.getMessage());
            return Map.of();
        }
    }

    // Parsed per key rather than letting one unparseable value abort the batch: a single corrupt
    // key should cost that one showtime its live count, not the whole page's.
    private Optional<Integer> parseSeatCount(String key, String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            log.warn("Discarding unparseable live seat count: key={}, value={}", key, value);
            return Optional.empty();
        }
    }
}
