package com.localink.cache.impl;

import com.localink.cache.RedisZSetOps;
import com.localink.cache.json.RedisJsonCodec;
import com.localink.cache.model.ZSetEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 基于 StringRedisTemplate 的 ZSet 结构默认实现。
 */
@RequiredArgsConstructor
public class DefaultRedisZSetOps implements RedisZSetOps {

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean add(String key, Object value, double score) {
        return Boolean.TRUE.equals(redisTemplate.opsForZSet().add(key, RedisJsonCodec.serialize(value), score));
    }

    @Override
    public boolean add(String key, Object value, double score, Duration ttl) {
        boolean added = add(key, value, score);
        redisTemplate.expire(key, ttl);
        return added;
    }

    @Override
    public Double incrementScore(String key, Object value, double delta) {
        return redisTemplate.opsForZSet().incrementScore(key, RedisJsonCodec.serialize(value), delta);
    }

    @Override
    public Long remove(String key, Object... values) {
        return redisTemplate.opsForZSet().remove(key, (Object[]) serializeAll(values));
    }

    @Override
    public Long size(String key) {
        return redisTemplate.opsForZSet().zCard(key);
    }

    @Override
    public Long rank(String key, Object value) {
        return redisTemplate.opsForZSet().rank(key, RedisJsonCodec.serialize(value));
    }

    @Override
    public Long reverseRank(String key, Object value) {
        return redisTemplate.opsForZSet().reverseRank(key, RedisJsonCodec.serialize(value));
    }

    @Override
    public Double score(String key, Object value) {
        return redisTemplate.opsForZSet().score(key, RedisJsonCodec.serialize(value));
    }

    @Override
    public <T> Set<T> range(String key, long start, long end, Class<T> type) {
        return convert(redisTemplate.opsForZSet().range(key, start, end), type);
    }

    @Override
    public <T> Set<T> reverseRange(String key, long start, long end, Class<T> type) {
        return convert(redisTemplate.opsForZSet().reverseRange(key, start, end), type);
    }

    @Override
    public <T> Set<ZSetEntry<T>> reverseRangeWithScore(String key, long start, long end, Class<T> type) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
        if (tuples == null) {
            return Set.of();
        }
        Set<ZSetEntry<T>> result = new LinkedHashSet<>(tuples.size());
        tuples.forEach(tuple -> {
            Double score = tuple.getScore();
            result.add(new ZSetEntry<>(RedisJsonCodec.deserialize(tuple.getValue(), type), score == null ? 0d : score));
        });
        return result;
    }

    @Override
    public <T> Set<T> reverseRangeByScore(String key, double min, double max, long offset, long count, Class<T> type) {
        return convert(redisTemplate.opsForZSet().reverseRangeByScore(key, min, max, offset, count), type);
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
