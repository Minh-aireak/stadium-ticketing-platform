package com.aireak.payment.adapter.out.idempotency;

import com.aireak.common.cache.LocalStringCache;
import com.aireak.payment.application.port.out.PaymentIdempotencyPort;
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
 * per-JVM cache of resolved paymentIds (see {@link #paymentIdCache}) so a repeat request landing
 * on this same instance skips both Redis and Postgres entirely.
 *
 * <p>Fails open on any Redis error — logs a warning and lets the caller proceed.
 * Callers relying on this guard for money-moving operations must pair it with a
 * DB-level backstop (see PaymentRepository#tryInsert) for the resulting race window.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonPaymentIdempotencyAdapter implements PaymentIdempotencyPort {

    // Local (per-JVM, NOT shared across pods) cache of resolved paymentIds only — never a "guard
    // held, no paymentId known yet" placeholder (that state is exactly the race window
    // tryAcquire/tryInitiate exist to arbitrate; caching it could let this instance answer a
    // still-unresolved attempt incorrectly). TTL matches PaymentService's own IDEMPOTENCY_TTL
    // (5 minutes, the Redis guard's own window) by convention — keep both in sync if either
    // changes. 10k entries: payment volume tracks booking volume ~1:1.
    private static final long LOCAL_CACHE_MAX_SIZE = 10_000;
    private static final Duration LOCAL_CACHE_TTL = Duration.ofMinutes(5);

    private final LocalStringCache paymentIdCache = new LocalStringCache(LOCAL_CACHE_MAX_SIZE, LOCAL_CACHE_TTL);

    private final RedissonClient redissonClient;

    @Override
    public boolean tryAcquire(String key, Duration ttl) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            boolean acquired = bucket.setIfAbsent("1", ttl);
            if (!acquired) {
                log.info("Idempotency guard already held: key={}", key);
            }
            return acquired;
        } catch (Exception e) {
            log.warn("Idempotency guard check failed, failing open: key={}, error={}", key, e.getMessage());
            return true;
        }
    }

    @Override
    public Optional<String> cachedPaymentId(String key) {
        return paymentIdCache.getIfPresent(key);
    }

    @Override
    public void remember(String key, String paymentId) {
        paymentIdCache.put(key, paymentId);
    }
}
