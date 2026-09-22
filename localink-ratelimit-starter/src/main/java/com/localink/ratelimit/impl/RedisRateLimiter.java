package com.localink.ratelimit.impl;

import com.localink.ratelimit.RateLimitResult;
import com.localink.ratelimit.RateLimiter;
import com.localink.ratelimit.config.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Lua 限流原语实现。TTL 策略内聚：桶按"补满耗时 × 2、下限 60s"（空闲桶自动清理不碍事，
 * 重建即满桶，语义无损）；窗口按"窗口长度 + 60s"（最后一次放行后窗口滑空、key 自然消失）。
 */
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    private static final String TOKEN_BUCKET_PREFIX = "rl:tb:";
    private static final String SLIDING_WINDOW_PREFIX = "rl:sw:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> tokenBucketScript;
    private final RedisScript<String> slidingWindowScript;
    private final RateLimitProperties properties;

    @Override
    public RateLimitResult tryAcquireTokenBucket(String key, int capacity, double ratePerSec, int permits) {
        long ttlSeconds = Math.max(60, (long) (capacity / ratePerSec) * 2);
        String result = redisTemplate.execute(tokenBucketScript,
                List.of(properties.getKeyPrefix() + TOKEN_BUCKET_PREFIX + key),
                String.valueOf(capacity), String.valueOf(ratePerSec), String.valueOf(permits),
                String.valueOf(System.currentTimeMillis()), String.valueOf(ttlSeconds));
        return parse(result);
    }

    @Override
    public RateLimitResult tryAcquireSlidingWindow(String key, int threshold, Duration window) {
        long ttlSeconds = window.toSeconds() + 60;
        String result = redisTemplate.execute(slidingWindowScript,
                List.of(properties.getKeyPrefix() + SLIDING_WINDOW_PREFIX + key),
                String.valueOf(threshold), String.valueOf(window.toMillis()),
                String.valueOf(System.currentTimeMillis()), UUID.randomUUID().toString(),
                String.valueOf(ttlSeconds));
        return parse(result);
    }

    private RateLimitResult parse(String result) {
        if (result == null || result.isEmpty()) {
            return RateLimitResult.rejected();
        }
        String[] parts = result.split("\\|");
        return "1".equals(parts[0])
                ? RateLimitResult.allowed(Long.parseLong(parts[1]))
                : RateLimitResult.rejected();
    }
}
