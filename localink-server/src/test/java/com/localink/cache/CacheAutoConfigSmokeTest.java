package com.localink.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class CacheAutoConfigSmokeTest {

    @Autowired
    private RedisCache redisCache;

    @Test
    void redisCacheBeanAutoConfigured() {
        assertNotNull(redisCache);
    }
}
