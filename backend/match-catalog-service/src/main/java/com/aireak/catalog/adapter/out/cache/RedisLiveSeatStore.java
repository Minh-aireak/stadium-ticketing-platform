package com.aireak.catalog.adapter.out.cache;

import com.aireak.catalog.application.port.out.LiveSeatAvailabilityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
        redissonClient.<String>getBucket(KEY_PREFIX + showtimeId).set(String.valueOf(availableSeats), TTL);
    }

    /** Fails open (empty) on any Redis error — callers fall back to whatever seat count they already have. */
    @Override
    public Optional<Integer> get(String showtimeId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + showtimeId);
            return Optional.ofNullable(bucket.get()).map(Integer::parseInt);
        } catch (Exception e) {
            log.warn("Redis read failed for live seat count, falling back to cached/DB value: showtimeId={}, error={}",
                    showtimeId, e.getMessage());
            return Optional.empty();
        }
    }
}
