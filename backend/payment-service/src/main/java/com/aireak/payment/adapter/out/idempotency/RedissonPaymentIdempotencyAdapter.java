package com.aireak.payment.adapter.out.idempotency;

import com.aireak.payment.application.port.out.PaymentIdempotencyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redisson implementation of {@link PaymentIdempotencyPort}.
 * Uses RBucket.setIfAbsent() (SETNX semantics) to detect duplicate payment requests.
 *
 * <p>Fails open on any Redis error — logs a warning and lets the caller proceed.
 * Callers relying on this guard for money-moving operations must pair it with a
 * DB-level backstop (see PaymentRepository#tryInsert) for the resulting race window.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonPaymentIdempotencyAdapter implements PaymentIdempotencyPort {

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
}
