package com.localink.cache.impl;

import com.localink.cache.KeyBuild;
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
    public boolean add(KeyBuild key, Object value, double score) {
        return Boolean.TRUE.equals(redisTemplate.opsForZSet().add(key.getKey(), RedisJsonCodec.serialize(value), score));
    }

    @Override
    public boolean add(KeyBuild key, Object value, double score, Duration ttl) {
        boolean added = add(key, value, score);
        redisTemplate.expire(key.getKey(), ttl);
        return added;
    }

    @Override
    public Double incrementScore(KeyBuild key, Object value, double delta) {
        return redisTemplate.opsForZSet().incrementScore(key.getKey(), RedisJsonCodec.serialize(value), delta);
    }

    @Override
    public Long remove(KeyBuild key, Object... values) {
        return redisTemplate.opsForZSet().remove(key.getKey(), (Object[]) serializeAll(values));
    }

    @Override
    public Long size(KeyBuild key) {
        return redisTemplate.opsForZSet().zCard(key.getKey());
    }

    @Override
    public Long rank(KeyBuild key, Object value) {
        return redisTemplate.opsForZSet().rank(key.getKey(), RedisJsonCodec.serialize(value));
    }

    @Override
    public Long reverseRank(KeyBuild key, Object value) {
        return redisTemplate.opsForZSet().reverseRank(key.getKey(), RedisJsonCodec.serialize(value));
    }

    @Override
    public Double score(KeyBuild key, Object value) {
        return redisTemplate.opsForZSet().score(key.getKey(), RedisJsonCodec.serialize(value));
    }

    @Override
    public <T> Set<T> range(KeyBuild key, long start, long end, Class<T> type) {
        return convert(redisTemplate.opsForZSet().range(key.getKey(), start, end), type);
    }

    @Override
    public <T> Set<T> reverseRange(KeyBuild key, long start, long end, Class<T> type) {
        return convert(redisTemplate.opsForZSet().reverseRange(key.getKey(), start, end), type);
    }

    @Override
    public <T> Set<ZSetEntry<T>> reverseRangeWithScore(KeyBuild key, long start, long end, Class<T> type) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key.getKey(), start, end);
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
    public <T> Set<T> reverseRangeByScore(KeyBuild key, double min, double max, long offset, long count, Class<T> type) {
        return convert(redisTemplate.opsForZSet().reverseRangeByScore(key.getKey(), min, max, offset, count), type);
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
