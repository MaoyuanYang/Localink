package com.localink.cache.test;

import com.localink.cache.LocalCache;
import com.localink.cache.LocalCacheRegistry;
import com.localink.cache.config.CacheProperties;
import com.localink.common.exception.LocalinkException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地缓存注册表纯单元测试：配置驱动建缓存、过期与容量淘汰、别名解析，不依赖 Redis。
 */
class LocalCacheRegistryTest {

    private CacheProperties propertiesWithShop(CacheProperties.LocalCacheSpec spec) {
        CacheProperties properties = new CacheProperties();
        properties.getLocal().getCaches().put("shop", spec);
        return properties;
    }

    @Test
    void configuredCacheIsCreatedAndTypedViewReadsBack() {
        CacheProperties.LocalCacheSpec spec = new CacheProperties.LocalCacheSpec();
        LocalCacheRegistry registry = new LocalCacheRegistry(propertiesWithShop(spec));

        LocalCache<String, String> cache = registry.cache("shop");
        cache.put("1", "tea");
        assertEquals("tea", cache.getIfPresent("1"));
    }

    @Test
    void unregisteredAliasFailsFast() {
        LocalCacheRegistry registry = new LocalCacheRegistry(new CacheProperties());

        LocalinkException ex = assertThrows(LocalinkException.class, () -> registry.cache("shop"));

        assertTrue(ex.getMessage().contains("未注册的本地缓存"));
    }

    @Test
    void expireAfterWriteEvictsEntry() throws InterruptedException {
        CacheProperties.LocalCacheSpec spec = new CacheProperties.LocalCacheSpec();
        spec.setExpireAfterWrite(Duration.ofMillis(100));
        LocalCache<String, String> cache = new LocalCacheRegistry(propertiesWithShop(spec)).cache("shop");

        cache.put("1", "tea");
        assertEquals("tea", cache.getIfPresent("1"));
        Thread.sleep(300);
        assertNull(cache.getIfPresent("1"));
    }

    @Test
    void maximumSizeBoundsTotalEntries() {
        CacheProperties.LocalCacheSpec spec = new CacheProperties.LocalCacheSpec();
        spec.setMaximumSize(2);
        LocalCache<String, String> cache = new LocalCacheRegistry(propertiesWithShop(spec)).cache("shop");

        for (int i = 0; i < 10; i++) {
            cache.put("key-" + i, "v" + i);
        }
        cache.cleanUp();

        int present = 0;
        for (int i = 0; i < 10; i++) {
            if (cache.getIfPresent("key-" + i) != null) {
                present++;
            }
        }
        assertTrue(present <= 2, "超过 maximumSize 后驻留条目数应不超过上限（W-TinyLFU 淘汰）, 实际=" + present);
    }

    @Test
    void putIgnoresNullKeyValueAndInvalidateRemoves() {
        LocalCache<String, String> cache = new LocalCacheRegistry(propertiesWithShop(new CacheProperties.LocalCacheSpec())).cache("shop");

        cache.put("1", null);
        cache.put(null, "tea");
        assertNull(cache.getIfPresent("1"));

        cache.put("1", "tea");
        cache.invalidate("1");
        assertNull(cache.getIfPresent("1"));
    }
}
