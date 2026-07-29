package com.aireak.booking.adapter.out.cache;

import com.aireak.booking.application.port.out.IdempotencyClaim;
import com.aireak.booking.application.port.out.IdempotencyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

//Redis-backed {@link IdempotencyStore}. Tracks request state: IN_PROGRESS or COMPLETED:<bookingId>.
@Slf4j
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

    // claim() runs on every createBooking request — bounded well below Redisson's own default
    // command timeout (3s) so a slow/unresponsive Redis fails fast into the fallback below
    // instead of stalling the request on it.
    private static final Duration CLAIM_TIMEOUT = Duration.ofMillis(300);

    private final RedissonClient redissonClient;

    /**
     * Fails open (reports {@link IdempotencyClaim.Claimed}) on ANY Redis trouble — not just a
     * genuine miss (no value stored) but also a timeout or connection error, both bounded by
     * {@link #CLAIM_TIMEOUT} rather than left to block on Redisson's own longer default. This is
     * a performance layer only (see {@link IdempotencyStore}): callers already fall back to
     * {@code BookingRepository#findByIdempotencyKey}, and ultimately to the DB's own
     * idempotency_key unique-constraint race handling, whenever this returns Claimed — so failing
     * open here can never let a real duplicate booking through, only cost a redundant DB check.
     */
    @Override
    public IdempotencyClaim claim(String idempotencyKey) {
        RBucket<String> bucket = bucket(idempotencyKey);
        try {
            boolean claimed = bucket.trySetAsync(IN_PROGRESS_MARKER, IN_PROGRESS_TTL.toSeconds(), TimeUnit.SECONDS)
                    .toCompletableFuture()
                    .get(CLAIM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (claimed) {
                return new IdempotencyClaim.Claimed();
            }
            String value = bucket.getAsync().toCompletableFuture()
                    .get(CLAIM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (value != null && value.startsWith(COMPLETED_PREFIX)) {
                return new IdempotencyClaim.Completed(value.substring(COMPLETED_PREFIX.length()));
            }
            return new IdempotencyClaim.InProgress();
        } catch (Exception e) {
            log.warn("Idempotency claim check timed out or failed, failing open: key={}, error={}",
                    idempotencyKey, e.getMessage());
            return new IdempotencyClaim.Claimed();
        }
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
