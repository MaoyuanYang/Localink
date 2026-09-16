package com.localink.lock.impl;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.lock.DistributedLock;
import com.localink.lock.LockType;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RedissonDistributedLock implements DistributedLock {

    private final RedissonClient redissonClient;

    public RedissonDistributedLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T runWithLock(String key, LockType type, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        RLock lock = resolve(key, type);
        boolean locked;
        try {
            locked = leaseTime == null || leaseTime.isNegative() || leaseTime.isZero()
                    ? lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS)
                    : lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalinkException(BaseCode.LOCK_TIMEOUT, "获取锁等待被中断");
        }
        if (!locked) {
            throw new LocalinkException(BaseCode.LOCK_TIMEOUT);
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public <T> Optional<T> tryWithLock(String key, LockType type, Duration leaseTime, Supplier<T> action) {
        RLock lock = resolve(key, type);
        boolean locked;
        try {
            locked = leaseTime == null || leaseTime.isNegative() || leaseTime.isZero()
                    ? lock.tryLock()
                    : lock.tryLock(0, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LocalinkException(BaseCode.LOCK_TIMEOUT, "获取锁等待被中断");
        }
        if (!locked) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(action.get());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private RLock resolve(String key, LockType type) {
        return switch (type) {
            case REENTRANT -> redissonClient.getLock(key);
            case FAIR -> redissonClient.getFairLock(key);
            case READ -> redissonClient.getReadWriteLock(key).readLock();
            case WRITE -> redissonClient.getReadWriteLock(key).writeLock();
        };
    }
}
