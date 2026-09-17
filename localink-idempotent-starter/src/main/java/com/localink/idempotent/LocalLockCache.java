package com.localink.idempotent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地公平锁仓库：Caffeine 承载 ReentrantLock（架构既定选型——与本地缓存同组件，减少依赖）。
 * 锁对象 48h 未使用即回收；回收时若有线程持有，后续调用会装载新锁对象，
 * 最坏退化为并发进入分布式公平锁串行——正确性由三级防护的后两层兜住。
 */
public class LocalLockCache {

    private final Cache<String, ReentrantLock> locks = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(48))
            .build();

    /**
     * 取（或原子装载）指定 key 的公平本地锁。
     */
    public ReentrantLock get(String key) {
        return locks.get(key, k -> new ReentrantLock(true));
    }
}
