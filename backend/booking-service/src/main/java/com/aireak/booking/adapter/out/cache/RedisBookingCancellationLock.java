package com.aireak.booking.adapter.out.cache;

import com.aireak.booking.application.port.out.BookingCancellationLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed {@link BookingCancellationLock}: {@code SET booking:cancel:lock:<bookingId> <token>
 * NX PX <lease>}, released by a script that deletes the key only while it still holds this request's
 * token.
 *
 * <p>The token is what makes the release safe. The lease is short (5 s) and the key expires on its
 * own, so a request that outlives it must not delete a claim a second request has taken since — a
 * plain {@code DEL} would. Not Redisson's {@code RLock}: that ties ownership to the acquiring thread
 * and renews through a watchdog, neither of which this needs; one key with a TTL is the whole
 * mechanism, and it reads the same in {@code redis-cli}.
 *
 * <p>Fails open, like {@code RedissonIdempotencyStore#claim}: on any Redis trouble the cancel goes
 * ahead without a claim, because the booking row's {@code @Version} still keeps two writes from both
 * landing (see the port). Every Redis call is bounded well inside the platform's command budget, so
 * a slow Redis costs a request at most {@link #COMMAND_TIMEOUT}, not a stall.
 */
@Slf4j
@Component
class RedisBookingCancellationLock implements BookingCancellationLock {

    private static final String KEY_PREFIX = "booking:cancel:lock:";

    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(300);

    private static final String RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final RedissonClient redissonClient;
    private final Duration lease;

    RedisBookingCancellationLock(RedissonClient redissonClient,
                                 @Value("${booking.cancellation.lock-lease:5s}") Duration lease) {
        this.redissonClient = redissonClient;
        this.lease = lease;
    }

    @Override
    public Optional<Claim> tryAcquire(String bookingId) {
        String key = KEY_PREFIX + bookingId;
        String token = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = redissonClient.getBucket(key, StringCodec.INSTANCE)
                    .setIfAbsentAsync(token, lease)
                    .toCompletableFuture()
                    .get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failOpen(key, e);
        } catch (Exception e) {
            return failOpen(key, e);
        }
        if (!acquired) {
            log.debug("Cancellation lock already held by another request: key={}", key);
            return Optional.empty();
        }
        log.info("Cancellation lock acquired: key={}, lease={}", key, lease);
        long acquiredAt = System.nanoTime();
        return Optional.of(() -> release(key, token, acquiredAt));
    }

    private Optional<Claim> failOpen(String key, Exception cause) {
        log.warn("Redis unreachable for the cancellation lock, cancelling without it (the booking's @Version "
                + "still serializes the write): key={}, error={}", key, cause.getMessage());
        return Optional.of(() -> { });
    }

    private void release(String key, String token, long acquiredAt) {
        long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAt);
        try {
            Long deleted = redissonClient.getScript(StringCodec.INSTANCE)
                    .<Long>evalAsync(RScript.Mode.READ_WRITE, RELEASE_SCRIPT, RScript.ReturnType.LONG,
                            List.of(key), token)
                    .toCompletableFuture()
                    .get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (deleted != null && deleted == 1L) {
                log.info("Cancellation lock released: key={}, heldMs={}", key, heldMs);
            } else {
                log.warn("Cancellation lock had already expired before release — the cancel outlived its "
                        + "lease, so another request may have held it meanwhile: key={}, lease={}, heldMs={}",
                        key, lease, heldMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logReleaseFailure(key, heldMs, e);
        } catch (Exception e) {
            logReleaseFailure(key, heldMs, e);
        }
    }

    private void logReleaseFailure(String key, long heldMs, Exception cause) {
        log.warn("Could not release the cancellation lock, it expires on its own within {}: key={}, heldMs={}, "
                + "error={}", lease, key, heldMs, cause.getMessage());
    }
}
