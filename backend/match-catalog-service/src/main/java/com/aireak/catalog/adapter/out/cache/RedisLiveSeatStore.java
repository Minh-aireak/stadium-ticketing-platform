package com.aireak.catalog.adapter.out.cache;

import com.aireak.catalog.application.port.out.LiveSeatAvailabilityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Redisson-backed {@link LiveSeatAvailabilityPort} — the read half of the live seat counter.
 *
 * <p>The counter it reads is seeded when a showtime is created and kept current by
 * {@link RedisSeatAvailabilityCounter}'s atomic decrements, so a value here is as fresh as the last
 * projected sale rather than as fresh as a cache TTL allows. This class never writes: see
 * {@link LiveSeatAvailabilityPort} for why the write side is a separate port.
 *
 * <p>Reads use {@code StringCodec} rather than Redisson's default binary codec because the writer's
 * Lua scripts store and parse the value as plain decimal text — see {@link LiveSeatKeys}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLiveSeatStore implements LiveSeatAvailabilityPort {

    private final RedissonClient redissonClient;

    /**
     * One MGET for the whole batch. Fails open (empty map) on any Redis error — callers fall back
     * to the seat counts they already have.
     */
    @Override
    public Map<String, Integer> getAll(Collection<String> showtimeIds) {
        if (showtimeIds.isEmpty()) {
            return Map.of();
        }
        String[] keys = showtimeIds.stream().distinct().map(LiveSeatKeys::of).toArray(String[]::new);
        try {
            Map<String, String> raw = redissonClient.getBuckets(StringCodec.INSTANCE).get(keys);
            Map<String, Integer> counts = HashMap.newHashMap(raw.size());
            raw.forEach((key, value) -> parseSeatCount(key, value)
                    .ifPresent(seats -> counts.put(LiveSeatKeys.showtimeIdIn(key), seats)));
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
