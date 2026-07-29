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
 *
 * <p><strong>{@code leaseTime} is intentionally ignored</strong>: calling the 2-arg
 * {@code tryLock(waitTime, unit)} instead of the 3-arg overload leaves Redisson's own lease
 * (30s default) in place and starts its <em>watchdog</em> — a background task that renews the
 * lease every ~10s for as long as this thread holds the lock. Passing an explicit leaseTime
 * disables the watchdog entirely, so a held action running longer than that fixed lease
 * (GC pause, slow downstream call, etc.) would lose the lock mid-operation and let a second
 * caller acquire it concurrently. The watchdog only stops renewing once {@code unlock()} below
 * runs, so a crash before that still self-expires — no permanent-hold risk.
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
            acquired = lock.tryLock(waitTime, unit);
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
