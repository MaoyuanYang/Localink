package com.localink.cache.impl;

import com.localink.cache.RedisHashOps;
import com.localink.cache.json.RedisJsonCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 StringRedisTemplate 的 Hash 结构默认实现。
 */
@RequiredArgsConstructor
public class DefaultRedisHashOps implements RedisHashOps {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void put(String key, String field, Object value) {
        redisTemplate.opsForHash().put(key, field, RedisJsonCodec.serialize(value));
    }

    @Override
    public void putAll(String key, Map<String, ?> map) {
        redisTemplate.opsForHash().putAll(key, serializeMap(map));
    }

    @Override
    public void putAll(String key, Map<String, ?> map, Duration ttl) {
        putAll(key, map);
        redisTemplate.expire(key, ttl);
    }

    @Override
    public <T> T get(String key, String field, Class<T> type) {
        Object raw = redisTemplate.opsForHash().get(key, field);
        return RedisJsonCodec.deserialize(raw == null ? null : raw.toString(), type);
    }

    @Override
    public Map<String, String> entries(String key) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
        Map<String, String> result = new HashMap<>(raw.size());
        raw.forEach((field, value) -> result.put(field.toString(), value == null ? null : value.toString()));
        return result;
    }

    @Override
    public boolean hasField(String key, String field) {
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(key, field));
    }

    @Override
    public Long delete(String key, String... fields) {
        return redisTemplate.opsForHash().delete(key, (Object[]) fields);
    }

    @Override
    public Long increment(String key, String field, long delta) {
        return redisTemplate.opsForHash().increment(key, field, delta);
    }

    private Map<String, String> serializeMap(Map<String, ?> map) {
        Map<String, String> result = new HashMap<>(map.size());
        map.forEach((field, value) -> result.put(field, RedisJsonCodec.serialize(value)));
        return result;
    }
}
