package com.localink.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.localink.cache.config.CacheProperties;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地缓存注册表：按 localink.cache.local.caches 配置初始化 Caffeine 实例，
 * 业务方以别名获取类型化视图。值的实际类型由调用方约定，框架不做运行时校验
 * （Caffeine 泛型运行时擦除，类型错误的写入会在读取方转型时暴露）。
 */
public class LocalCacheRegistry {

    private final Map<String, LocalCache<Object, Object>> caches = new HashMap<>();

    public LocalCacheRegistry(CacheProperties cacheProperties) {
        Map<String, CacheProperties.LocalCacheSpec> configured = cacheProperties.getLocal().getCaches();
        for (Map.Entry<String, CacheProperties.LocalCacheSpec> entry : configured.entrySet()) {
            CacheProperties.LocalCacheSpec spec = entry.getValue();
            Cache<Object, Object> cache = Caffeine.newBuilder()
                    .maximumSize(spec.getMaximumSize())
                    .expireAfterWrite(spec.getExpireAfterWrite())
                    .build();
            caches.put(entry.getKey(), new CaffeineLocalCache<>(cache));
        }
    }

    /**
     * 按别名获取类型化本地缓存视图，未注册的别名快速失败。
     */
    @SuppressWarnings("unchecked")
    public <K, V> LocalCache<K, V> cache(String alias) {
        LocalCache<Object, Object> cache = caches.get(alias);
        if (cache == null) {
            throw new LocalinkException(BaseCode.SYSTEM_ERROR, "未注册的本地缓存: " + alias);
        }
        return (LocalCache<K, V>) cache;
    }

    private static final class CaffeineLocalCache<K, V> implements LocalCache<K, V> {

        private final Cache<K, V> cache;

        private CaffeineLocalCache(Cache<K, V> cache) {
            this.cache = cache;
        }

        @Override
        public V getIfPresent(K key) {
            if (key == null) {
                return null;
            }
            return cache.getIfPresent(key);
        }

        @Override
        public void put(K key, V value) {
            if (key == null || value == null) {
                return;
            }
            cache.put(key, value);
        }

        @Override
        public void invalidate(K key) {
            if (key != null) {
                cache.invalidate(key);
            }
        }

        @Override
        public void cleanUp() {
            cache.cleanUp();
        }
    }
}
