package com.aireak.inventory.adapter.out.lock;

import com.aireak.inventory.application.port.out.DistributedLockPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
            // Three-arg tryLock, so leaseTime is actually honoured. The two-arg form silently
            // ignores it and hands the lock to the Redisson watchdog instead, which renews every
            // ~10s for as long as the JVM lives: an instance that stalls while holding a lock would
            // block the other one indefinitely, and one that is SIGKILLed would hold it until the
            // watchdog timeout (~30s) rather than for leaseTime. Releasing early is the safer
            // failure here -- every path under this lock has a second guard: putIfAbsent per key
            // in RedissonSeatHoldAdapter for hold, reserve and their rollbacks, and Seat#sell
            // refusing a seat already SOLD to a different booking for confirm. So a lease that
            // expires mid-action cannot double-sell a seat.
            //
            // That claim has been wrong twice. It used to credit a @Version on the aggregate,
            // which never incremented and was dropped in V6. It then covered "reserve" while
            // confirmHold's hand-over branch wrote with an unconditional put, so an expired lease
            // there could overwrite a hold another customer had just taken -- two buyers each
            // believing they held the seat until one lost at Seat#sell, after paying. That branch
            // is putIfAbsent now. Anything added under this lock has to bring its own guard, or
            // this comment goes stale a third time.
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
