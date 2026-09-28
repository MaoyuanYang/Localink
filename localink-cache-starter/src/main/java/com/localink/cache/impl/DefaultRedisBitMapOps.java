package com.localink.cache.impl;

import com.localink.cache.KeyBuild;
import com.localink.cache.RedisBitMapOps;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 基于 StringRedisTemplate 的 BitMap 默认实现。
 */
@RequiredArgsConstructor
public class DefaultRedisBitMapOps implements RedisBitMapOps {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void setBit(KeyBuild key, long offset, boolean value) {
        redisTemplate.opsForValue().setBit(key.getKey(), offset, value);
    }

    @Override
    public boolean getBit(KeyBuild key, long offset) {
        Boolean bit = redisTemplate.opsForValue().getBit(key.getKey(), offset);
        return Boolean.TRUE.equals(bit);
    }

    @Override
    public long bitCount(KeyBuild key) {
        // ValueOperations 在 3.5 无 bitCount 门面，BITCOUNT 走 connection 层回调
        Long count = redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.bitCount(key.getKey().getBytes(StandardCharsets.UTF_8)));
        return count == null ? 0 : count;
    }

    @Override
    public long getUnsigned(KeyBuild key, int bits, long offset) {
        BitFieldSubCommands sub = BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(bits))
                .valueAt(offset);
        List<Long> result = redisTemplate.opsForValue().bitField(key.getKey(), sub);
        if (result == null || result.isEmpty() || result.get(0) == null) {
            return 0L;
        }
        // BITFIELD 的位序与 SETBIT 相反（实测 Redis 7.4：SETBIT 的第 k 位落在返回值的第
        // bits-1-k 位）——本封装翻正为 SETBIT 位序，调用方按 offset 升序=低位理解即可
        return Long.reverse(result.get(0)) >>> (64 - bits);
    }
}
