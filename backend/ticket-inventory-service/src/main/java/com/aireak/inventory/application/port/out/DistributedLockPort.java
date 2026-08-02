package com.aireak.inventory.application.port.out;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public interface DistributedLockPort {

    /**
     * Executes the given action while holding a distributed lock for the key.
     *
     * @param lockKey  unique key identifying the resource to lock
     * @param waitTime maximum time to wait for the lock
     * @param leaseTime lock expiry time (auto-released after this duration)
     * @param unit     time unit
     * @param action   the action to execute under the lock
     * @param <T>      return type of the action
     * @return result of the action
     * @throws RuntimeException if the lock cannot be acquired within waitTime
     */
    <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action);
}
