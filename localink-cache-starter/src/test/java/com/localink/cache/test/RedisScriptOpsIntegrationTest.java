package com.localink.cache.test;

import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RedisScriptOpsIntegrationTest {

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<KeyBuild> usedKeys = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        redisCache.delete(usedKeys);
    }

    @Test
    void executeRunsScriptWithGovernedKeysAndArgs() {
        KeyBuild key = track("test:m36:counter:%s", "a");
        RedisScript<Long> script = RedisScript.of("return redis.call('INCRBY', KEYS[1], ARGV[1])", Long.class);

        assertEquals(5L, redisCache.scripts().execute(script, List.of(key), "5"));
        assertEquals(8L, redisCache.scripts().execute(script, List.of(key), "3"));
    }

    @Test
    void executeSupportsMultiKeyAtomicScript() {
        KeyBuild stringKey = track("test:m36:str:%s", "a");
        KeyBuild setKey = track("test:m36:set:%s", "a");
        RedisScript<Long> script = RedisScript.of(
                "redis.call('SET', KEYS[1], ARGV[1]) redis.call('SADD', KEYS[2], ARGV[2]) return 1", Long.class);

        Long result = redisCache.scripts().execute(script, List.of(stringKey, setKey), "v1", "u1");

        assertEquals(1L, result);
        assertEquals("v1", redisCache.strings().getString(stringKey));
        assertTrue(redisCache.sets().isMember(setKey, "u1"));
    }

    private KeyBuild track(String template, String arg) {
        KeyBuild key = keyBuilder.build(() -> template, arg);
        usedKeys.add(key);
        return key;
    }
}
