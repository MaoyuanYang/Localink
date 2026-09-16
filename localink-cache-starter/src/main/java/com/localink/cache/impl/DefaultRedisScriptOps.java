package com.localink.cache.impl;

import com.localink.cache.KeyBuild;
import com.localink.cache.RedisScriptOps;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.List;

public class DefaultRedisScriptOps implements RedisScriptOps {

    private final StringRedisTemplate redisTemplate;

    public DefaultRedisScriptOps(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public <T> T execute(RedisScript<T> script, List<KeyBuild> keys, Object... args) {
        List<String> rawKeys = keys.stream().map(KeyBuild::getKey).toList();
        String[] rawArgs = Arrays.stream(args).map(String::valueOf).toArray(String[]::new);
        return redisTemplate.execute(script, rawKeys, rawArgs);
    }
}
