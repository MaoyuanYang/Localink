package com.localink.cache.impl;

import com.localink.cache.KeyBuild;
import com.localink.cache.RedisCache;
import com.localink.cache.RedisHashOps;
import com.localink.cache.RedisSetOps;
import com.localink.cache.RedisStringOps;
import com.localink.cache.RedisZSetOps;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * 基于 StringRedisTemplate 的 RedisCache 默认实现。
 */
public class DefaultRedisCache implements RedisCache {

    private final StringRedisTemplate redisTemplate;
    private final RedisStringOps stringOps;
    private final RedisHashOps hashOps;
    private final RedisSetOps setOps;
    private final RedisZSetOps zsetOps;

    public DefaultRedisCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringOps = new DefaultRedisStringOps(redisTemplate);
        this.hashOps = new DefaultRedisHashOps(redisTemplate);
        this.setOps = new DefaultRedisSetOps(redisTemplate);
        this.zsetOps = new DefaultRedisZSetOps(redisTemplate);
    }

    @Override
    public boolean hasKey(KeyBuild key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key.getKey()));
    }

    @Override
    public void delete(KeyBuild key) {
        redisTemplate.delete(key.getKey());
    }

    @Override
    public void delete(Collection<KeyBuild> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<String> rawKeys = keys.stream().map(KeyBuild::getKey).toList();
        redisTemplate.delete(rawKeys);
    }

    @Override
    public boolean expire(KeyBuild key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.expire(key.getKey(), ttl));
    }

    @Override
    public Long getExpire(KeyBuild key) {
        return redisTemplate.getExpire(key.getKey());
    }

    @Override
    public RedisStringOps strings() {
        return stringOps;
    }

    @Override
    public RedisHashOps hashes() {
        return hashOps;
    }

    @Override
    public RedisSetOps sets() {
        return setOps;
    }

    @Override
    public RedisZSetOps zsets() {
        return zsetOps;
    }
}
