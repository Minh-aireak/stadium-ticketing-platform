package com.aireak.booking.adapter.out.cache;

import com.aireak.booking.application.port.out.IdempotencyClaim;
import com.aireak.booking.application.port.out.IdempotencyStore;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

//Redis-backed {@link IdempotencyStore}. Tracks request state: IN_PROGRESS or COMPLETED:<bookingId>.
@Component
@RequiredArgsConstructor
class RedissonIdempotencyStore implements IdempotencyStore {

    private static final String KEY_PREFIX = "booking:idempotency:";
    private static final String IN_PROGRESS_MARKER = "IN_PROGRESS";
    private static final String COMPLETED_PREFIX = "COMPLETED:";

    // 90s TTL to cover worst-case saga execution (retries/timeouts) or auto-release dead requests
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(90);

    // 24h retention window for replaying completed booking results on client retries
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    private final RedissonClient redissonClient;

    @Override
    public IdempotencyClaim claim(String idempotencyKey) {
        RBucket<String> bucket = bucket(idempotencyKey);
        if (bucket.trySet(IN_PROGRESS_MARKER, IN_PROGRESS_TTL.toSeconds(), TimeUnit.SECONDS)) {
            return new IdempotencyClaim.Claimed();
        }
        String value = bucket.get();
        if (value != null && value.startsWith(COMPLETED_PREFIX)) {
            return new IdempotencyClaim.Completed(value.substring(COMPLETED_PREFIX.length()));
        }
        return new IdempotencyClaim.InProgress();
    }

    @Override
    public void complete(String idempotencyKey, String bookingId) {
        bucket(idempotencyKey).set(COMPLETED_PREFIX + bookingId, COMPLETED_TTL.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void release(String idempotencyKey) {
        bucket(idempotencyKey).delete();
    }

    private RBucket<String> bucket(String idempotencyKey) {
        return redissonClient.getBucket(KEY_PREFIX + idempotencyKey);
    }
}
