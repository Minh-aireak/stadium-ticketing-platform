package com.aireak.inventory.adapter.out.lock;

import com.aireak.inventory.application.port.out.DistributedLockPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson implementation of {@link DistributedLockPort}.
 * Uses RLock.tryLock() — non-blocking wait up to waitTime.
 *
 * <p>Hexagonal rule: only this class knows about Redisson. Application layer
 * sees only {@link DistributedLockPort}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedissonDistributedLockAdapter implements DistributedLockPort {

    private final RedissonClient redissonClient;

    @Override
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                  TimeUnit unit, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime, leaseTime, unit);
            if (!acquired) {
                throw new IllegalStateException(
                        "Could not acquire distributed lock for key: " + lockKey +
                        " within " + waitTime + " " + unit);
            }
            log.debug("Lock acquired: {}", lockKey);
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring lock: " + lockKey, e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Lock released: {}", lockKey);
            }
        }
    }
}
