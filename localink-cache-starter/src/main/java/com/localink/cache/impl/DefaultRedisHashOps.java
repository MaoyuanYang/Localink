package com.localink.cache.impl;

import com.localink.cache.KeyBuild;
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
    public void put(KeyBuild key, String field, Object value) {
        redisTemplate.opsForHash().put(key.getKey(), field, RedisJsonCodec.serialize(value));
    }

    @Override
    public void putAll(KeyBuild key, Map<String, ?> map) {
        redisTemplate.opsForHash().putAll(key.getKey(), serializeMap(map));
    }

    @Override
    public void putAll(KeyBuild key, Map<String, ?> map, Duration ttl) {
        putAll(key, map);
        redisTemplate.expire(key.getKey(), ttl);
    }

    @Override
    public <T> T get(KeyBuild key, String field, Class<T> type) {
        Object raw = redisTemplate.opsForHash().get(key.getKey(), field);
        return RedisJsonCodec.deserialize(raw == null ? null : raw.toString(), type);
    }

    @Override
    public Map<String, String> entries(KeyBuild key) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key.getKey());
        Map<String, String> result = new HashMap<>(raw.size());
        raw.forEach((field, value) -> result.put(field.toString(), value == null ? null : value.toString()));
        return result;
    }

    @Override
    public boolean hasField(KeyBuild key, String field) {
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(key.getKey(), field));
    }

    @Override
    public Long delete(KeyBuild key, String... fields) {
        return redisTemplate.opsForHash().delete(key.getKey(), (Object[]) fields);
    }

    @Override
    public Long increment(KeyBuild key, String field, long delta) {
        return redisTemplate.opsForHash().increment(key.getKey(), field, delta);
    }

    private Map<String, String> serializeMap(Map<String, ?> map) {
        Map<String, String> result = new HashMap<>(map.size());
        map.forEach((field, value) -> result.put(field, RedisJsonCodec.serialize(value)));
        return result;
    }
}
