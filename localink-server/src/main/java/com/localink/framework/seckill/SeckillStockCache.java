package com.localink.framework.seckill;

import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀库存的 Redis 预热与 key 治理。预热永远以 DB 当前值为准覆盖式写入（幂等，重启/更新皆安全）；
 * TTL 设为到活动结束，活动后 key 自动清理。
 */
@Component
@RequiredArgsConstructor
public class SeckillStockCache {

    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    /**
     * 以 DB 库存覆盖式重灌库存 key；活动已结束或无结束时间则不灌。
     */
    public void warm(Long voucherId, Integer stock, LocalDateTime endTime) {
        if (stock == null || endTime == null || !endTime.isAfter(LocalDateTime.now())) {
            return;
        }
        KeyBuild stockKey = keyBuilder.build(KeyManage.SECKILL_STOCK, voucherId);
        redisCache.strings().set(stockKey, String.valueOf(stock));
        redisCache.expire(stockKey, ttlTo(endTime));
    }

    /**
     * 删除库存、已购集合与流水三 key（券删除时调用）。
     */
    public void evict(Long voucherId) {
        redisCache.delete(List.of(stockKey(voucherId), orderUsersKey(voucherId), flowKey(voucherId)));
    }

    public KeyBuild stockKey(Long voucherId) {
        return keyBuilder.build(KeyManage.SECKILL_STOCK, voucherId);
    }

    public KeyBuild orderUsersKey(Long voucherId) {
        return keyBuilder.build(KeyManage.SECKILL_ORDER_USERS, voucherId);
    }

    public KeyBuild flowKey(Long voucherId) {
        return keyBuilder.build(KeyManage.SECKILL_FLOW, voucherId);
    }

    private static Duration ttlTo(LocalDateTime endTime) {
        Duration ttl = Duration.between(LocalDateTime.now(), endTime);
        return ttl.toSeconds() < 1 ? Duration.ofSeconds(1) : ttl;
    }
}
