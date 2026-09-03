package com.aireak.payment.adapter.out.idempotency;

import com.aireak.common.cache.LocalStringCache;
import com.aireak.payment.application.port.out.PaymentIdempotencyPort;
import com.aireak.payment.application.port.out.PaymentIdempotencyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redisson implementation of {@link PaymentIdempotencyPort}.
 * Uses RBucket.setIfAbsent() (SETNX semantics) to detect duplicate payment requests, fronted by a
 * per-JVM cache of resolved paymentIds (see {@link #paymentIdCache}) checked first in
 * {@link #acquire} so a repeat request landing on this same instance skips both Redis and
 * Postgres entirely.
 *
 * <p>Fails open on any Redis error — logs a warning and lets the caller proceed.
 * Callers relying on this guard for money-moving operations must pair it with a
 * DB-level backstop (see PaymentRepository#tryInsert) for the resulting race window.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonPaymentIdempotencyAdapter implements PaymentIdempotencyPort {

    // Single source of truth for both the Redis guard's TTL and the local cache's expiry — a
    // repeat attempt is only meaningfully "the same request" within this window; past it, a new
    // attempt is treated as fresh. 10k entries: payment volume tracks booking volume ~1:1.
    private static final Duration GUARD_TTL = Duration.ofMinutes(5);
    private static final long LOCAL_CACHE_MAX_SIZE = 10_000;

    // Local (per-JVM, NOT shared across pods) cache of resolved paymentIds only — never a "guard
    // held, no paymentId known yet" placeholder (that state is exactly the race window
    // tryAcquire/tryInitiate exist to arbitrate; caching it could let this instance answer a
    // still-unresolved attempt incorrectly).
    private final LocalStringCache paymentIdCache = new LocalStringCache(LOCAL_CACHE_MAX_SIZE, GUARD_TTL);

    private final RedissonClient redissonClient;

    @Override
    public PaymentIdempotencyResult acquire(String key) {
        Optional<String> cached = paymentIdCache.getIfPresent(key);
        if (cached.isPresent()) {
            return new PaymentIdempotencyResult.Cached(cached.get());
        }

        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            boolean acquired = bucket.setIfAbsent("1", GUARD_TTL);
            if (acquired) {
                return new PaymentIdempotencyResult.Acquired();
            }
            log.info("Idempotency guard already held: key={}", key);
            return new PaymentIdempotencyResult.AlreadyHeld();
        } catch (Exception e) {
            log.warn("Idempotency guard check failed, failing open: key={}, error={}", key, e.getMessage());
            return new PaymentIdempotencyResult.Acquired();
        }
    }

    @Override
    public void remember(String key, String paymentId) {
        paymentIdCache.put(key, paymentId);
    }

    /**
     * Swallows every Redis failure, like {@link #acquire}, and for a sharper reason: its only
     * caller runs it in a catch block immediately before rethrowing the failure that got it there
     * (see {@code PaymentService#execute}). A throw from here would replace that exception with a
     * Redis one, so a caller whose payment row could not be committed would be told about a cache.
     *
     * <p>The cost of losing this delete is exactly the behaviour that existed before it: the key
     * stays held for the rest of {@link #GUARD_TTL} and a retry inside that window is refused for
     * a payment that was never created.
     *
     * <p>Safe even when {@link #acquire} failed open and never wrote a key at all. The worst that
     * costs is deleting a guard a concurrent attempt had legitimately taken, and that attempt is
     * already covered by the DB-level backstop this class's javadoc requires — its INSERT is
     * refused by {@code uq_payments_booking_id} and resolved through {@code tryInitiate}'s
     * AlreadyExists branch. A redundant round trip, never a second charge.
     */
    @Override
    public void release(String key) {
        try {
            redissonClient.getBucket(key).delete();
        } catch (Exception e) {
            log.warn("Could not release the idempotency guard; it expires on its own in {}s: key={}, error={}",
                    GUARD_TTL.toSeconds(), key, e.getMessage());
        }
        // Defensive: release is only ever reached for a key that never resolved to a paymentId, so
        // there is nothing cached to evict — invalidating anyway closes off any future misuse.
        paymentIdCache.invalidate(key);
    }
}
