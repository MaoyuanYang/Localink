package com.localink.cache.impl;

import com.localink.cache.RedisSetOps;
import com.localink.cache.json.RedisJsonCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 基于 StringRedisTemplate 的 Set 结构默认实现。
 */
@RequiredArgsConstructor
public class DefaultRedisSetOps implements RedisSetOps {

    private final StringRedisTemplate redisTemplate;

    @Override
    public Long add(String key, Object... values) {
        return redisTemplate.opsForSet().add(key, serializeAll(values));
    }

    @Override
    public Long remove(String key, Object... values) {
        return redisTemplate.opsForSet().remove(key, (Object[]) serializeAll(values));
    }

    @Override
    public boolean isMember(String key, Object value) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, RedisJsonCodec.serialize(value)));
    }

    @Override
    public <T> Set<T> members(String key, Class<T> type) {
        return convert(redisTemplate.opsForSet().members(key), type);
    }

    @Override
    public <T> Set<T> intersect(String key, String otherKey, Class<T> type) {
        return convert(redisTemplate.opsForSet().intersect(key, otherKey), type);
    }

    @Override
    public Long size(String key) {
        return redisTemplate.opsForSet().size(key);
    }

    private String[] serializeAll(Object... values) {
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = RedisJsonCodec.serialize(values[i]);
        }
        return result;
    }

    private <T> Set<T> convert(Set<String> raw, Class<T> type) {
        if (raw == null) {
            return Set.of();
        }
        Set<T> result = new LinkedHashSet<>(raw.size());
        raw.forEach(item -> result.add(RedisJsonCodec.deserialize(item, type)));
        return result;
    }
}
