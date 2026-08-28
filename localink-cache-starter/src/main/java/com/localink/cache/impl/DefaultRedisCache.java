package com.localink.cache.impl;

import com.localink.cache.RedisCache;
import com.localink.cache.RedisHashOps;
import com.localink.cache.RedisSetOps;
import com.localink.cache.RedisStringOps;
import com.localink.cache.RedisZSetOps;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;

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
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public void delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        redisTemplate.delete(keys);
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, ttl));
    }

    @Override
    public Long getExpire(String key) {
        return redisTemplate.getExpire(key);
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
