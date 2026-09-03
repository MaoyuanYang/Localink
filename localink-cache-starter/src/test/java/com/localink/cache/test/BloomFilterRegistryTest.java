package com.localink.cache.test;

import com.localink.cache.BloomFilterRegistry;
import com.localink.cache.KeyBuilder;
import com.localink.cache.config.CacheProperties;
import com.localink.common.exception.LocalinkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布隆过滤器注册表纯单元测试：配置缺漏在构造期快速失败，不依赖 Redis。
 */
class BloomFilterRegistryTest {

    @Test
    void blankKeyTemplateFailsFastAtConstruction() {
        CacheProperties properties = new CacheProperties();
        properties.getBloom().getFilters().put("demo", new CacheProperties.Filter());

        LocalinkException ex = assertThrows(LocalinkException.class,
                () -> new BloomFilterRegistry(null, new KeyBuilder("lk:"), properties));

        assertTrue(ex.getMessage().contains("key-template"));
    }
}
