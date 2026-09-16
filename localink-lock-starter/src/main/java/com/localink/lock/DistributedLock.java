package com.localink.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 命令式分布式锁：拿锁 → 执行 → 释放。
 * waitTime 内未获取到锁抛 LOCK_TIMEOUT；leaseTime 传 null 或非正数走 Redisson 看门狗自动续期，
 * 适用于业务执行时长不可预估的场景（不会因锁过期被他人抢占后误释放）。
 */
public interface DistributedLock {

    /**
     * 在锁保护下执行有返回值的动作。
     *
     * @param key       完整 Redis key（含环境前缀，由调用方治理）
     * @param type      锁类型
     * @param waitTime  获取锁的最长等待，超时抛 LOCK_TIMEOUT
     * @param leaseTime 持锁时长；null 或非正数表示看门狗续期
     * @param action    临界区动作
     */
    <T> T runWithLock(String key, LockType type, Duration waitTime, Duration leaseTime, Supplier<T> action);

    /**
     * 在锁保护下执行无返回值的动作。
     */
    default void runWithLock(String key, LockType type, Duration waitTime, Duration leaseTime, Runnable action) {
        runWithLock(key, type, waitTime, leaseTime, () -> {
            action.run();
            return null;
        });
    }
}
