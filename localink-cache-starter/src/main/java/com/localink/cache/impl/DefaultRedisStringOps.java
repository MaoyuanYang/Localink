package com.localink.cache.impl;

import com.localink.cache.KeyBuild;
import com.localink.cache.RedisStringOps;
import com.localink.cache.json.RedisJsonCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 基于 StringRedisTemplate 的 String 结构默认实现。
 */
@RequiredArgsConstructor
public class DefaultRedisStringOps implements RedisStringOps {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void set(KeyBuild key, Object value) {
        redisTemplate.opsForValue().set(key.getKey(), RedisJsonCodec.serialize(value));
    }

    @Override
    public void set(KeyBuild key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key.getKey(), RedisJsonCodec.serialize(value), ttl);
    }

    @Override
    public boolean setIfAbsent(KeyBuild key, Object value, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key.getKey(), RedisJsonCodec.serialize(value), ttl));
    }

    @Override
    public <T> T get(KeyBuild key, Class<T> type) {
        return RedisJsonCodec.deserialize(redisTemplate.opsForValue().get(key.getKey()), type);
    }

    @Override
    public String getString(KeyBuild key) {
        return redisTemplate.opsForValue().get(key.getKey());
    }

    @Override
    public <T> List<T> getList(KeyBuild key, Class<T> elementType) {
        return RedisJsonCodec.deserializeList(redisTemplate.opsForValue().get(key.getKey()), elementType);
    }

    @Override
    public String getAndDelete(KeyBuild key) {
        return redisTemplate.opsForValue().getAndDelete(key.getKey());
    }

    @Override
    public Long increment(KeyBuild key, long delta) {
        return redisTemplate.opsForValue().increment(key.getKey(), delta);
    }
}
