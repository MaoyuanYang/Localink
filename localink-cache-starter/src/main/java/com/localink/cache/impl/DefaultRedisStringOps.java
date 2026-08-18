package com.localink.cache.impl;

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
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, RedisJsonCodec.serialize(value));
    }

    @Override
    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, RedisJsonCodec.serialize(value), ttl);
    }

    @Override
    public boolean setIfAbsent(String key, Object value, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, RedisJsonCodec.serialize(value), ttl));
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        return RedisJsonCodec.deserialize(redisTemplate.opsForValue().get(key), type);
    }

    @Override
    public String getString(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public <T> List<T> getList(String key, Class<T> elementType) {
        return RedisJsonCodec.deserializeList(redisTemplate.opsForValue().get(key), elementType);
    }

    @Override
    public String getAndDelete(String key) {
        return redisTemplate.opsForValue().getAndDelete(key);
    }

    @Override
    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }
}
