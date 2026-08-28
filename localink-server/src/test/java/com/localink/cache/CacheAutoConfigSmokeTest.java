package com.localink.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class CacheAutoConfigSmokeTest {

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Test
    void redisCacheBeanAutoConfigured() {
        assertNotNull(redisCache);
    }

    @Test
    void keyBuilderBeanAutoConfiguredWithDefaultPrefix() {
        assertNotNull(keyBuilder);
        assertEquals("lk:test:any", keyBuilder.build(() -> "test:any").getKey());
    }
}
