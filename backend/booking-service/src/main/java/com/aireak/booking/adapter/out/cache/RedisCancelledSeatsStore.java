package com.aireak.booking.adapter.out.cache;

import com.aireak.booking.application.port.out.CancelledSeatsStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis-backed {@link CancelledSeatsStore}: {@code booking:cancel:seats:<bookingId>} holds the
 * booking's cancelled seats as a comma-separated string — the same shape {@code bookings.seat_codes}
 * uses — with a TTL, so {@code redis-cli GET} shows exactly what a repeated cancel is answered from.
 *
 * <p>One {@code SET} with an expiry, written after the database has committed: whole, never merged,
 * so there is no read-modify-write to race. Fails open both ways (see the port): a read that fails
 * reads as nothing recorded, a write that fails is logged and left for the next request to redo.
 */
@Slf4j
@Component
class RedisCancelledSeatsStore implements CancelledSeatsStore {

    private static final String KEY_PREFIX = "booking:cancel:seats:";
    private static final String SEPARATOR = ",";

    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final Duration WRITE_TIMEOUT = Duration.ofMillis(500);

    private final RedissonClient redissonClient;
    private final Duration ttl;

    RedisCancelledSeatsStore(RedissonClient redissonClient,
                             @Value("${booking.cancellation.state-ttl:24h}") Duration ttl) {
        this.redissonClient = redissonClient;
        this.ttl = ttl;
    }

    @Override
    public Set<String> find(String bookingId) {
        try {
            String value = bucket(bookingId).getAsync().toCompletableFuture()
                    .get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (value == null || value.isBlank()) {
                return Set.of();
            }
            return Arrays.stream(value.split(SEPARATOR)).collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return readFailed(bookingId, e);
        } catch (Exception e) {
            return readFailed(bookingId, e);
        }
    }

    @Override
    public void record(String bookingId, Collection<String> cancelledSeatCodes) {
        if (cancelledSeatCodes.isEmpty()) {
            return;
        }
        String value = String.join(SEPARATOR, cancelledSeatCodes);
        try {
            bucket(bookingId).setAsync(value, ttl).toCompletableFuture()
                    .get(WRITE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Cancelled seats recorded in Redis: key={}, seats={}, ttl={}", KEY_PREFIX + bookingId, value, ttl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeFailed(bookingId, value, e);
        } catch (Exception e) {
            writeFailed(bookingId, value, e);
        }
    }

    private Set<String> readFailed(String bookingId, Exception cause) {
        log.warn("Could not read cancelled seats from Redis, falling back to the database: key={}, error={}",
                KEY_PREFIX + bookingId, cause.getMessage());
        return Set.of();
    }

    private void writeFailed(String bookingId, String value, Exception cause) {
        log.warn("Could not record cancelled seats in Redis; the next repeat is answered from the database "
                + "and rewrites it: key={}, seats={}, error={}", KEY_PREFIX + bookingId, value, cause.getMessage());
    }

    private RBucket<String> bucket(String bookingId) {
        return redissonClient.getBucket(KEY_PREFIX + bookingId, StringCodec.INSTANCE);
    }
}
