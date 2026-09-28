package com.localink.cache.impl;

import com.localink.cache.KeyBuild;
import com.localink.cache.RedisHyperLogLogOps;
import com.localink.cache.json.RedisJsonCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 StringRedisTemplate 的 HyperLogLog 默认实现。
 */
@RequiredArgsConstructor
public class DefaultRedisHyperLogLogOps implements RedisHyperLogLogOps {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void add(KeyBuild key, Object... values) {
        String[] members = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            members[i] = RedisJsonCodec.serialize(values[i]);
        }
        redisTemplate.opsForHyperLogLog().add(key.getKey(), members);
    }

    @Override
    public long count(KeyBuild... keys) {
        String[] rawKeys = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            rawKeys[i] = keys[i].getKey();
        }
        Long size = redisTemplate.opsForHyperLogLog().size(rawKeys);
        return size == null ? 0 : size;
    }

    @Override
    public void union(KeyBuild destination, KeyBuild... sourceKeys) {
        String[] rawSources = new String[sourceKeys.length];
        for (int i = 0; i < sourceKeys.length; i++) {
            rawSources[i] = sourceKeys[i].getKey();
        }
        redisTemplate.opsForHyperLogLog().union(destination.getKey(), rawSources);
    }
}
