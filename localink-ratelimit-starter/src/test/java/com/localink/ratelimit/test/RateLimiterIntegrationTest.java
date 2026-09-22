package com.localink.ratelimit.test;

import com.localink.ratelimit.RateLimitResult;
import com.localink.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3.13 限流原语验证：令牌桶（容量/补充/封顶）、滑动窗口（阈值/清窗外/拒绝不占窗）、
 * 并发原子性（Lua 单线程执行，放行数恰等于额度）、key 前缀与 TTL。
 * 时间推进用"回拨 Hash last 字段"模拟 elapsed，免真实睡眠；Lua 内取不了时间，状态本就在 Java 时钟下。
 */
@SpringBootTest
class RateLimiterIntegrationTest {

    private static final double FROZEN_RATE = 0.0001;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<String> usedKeys = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        usedKeys.forEach(k -> {
            redisTemplate.delete("lk:rl:tb:" + k);
            redisTemplate.delete("lk:rl:sw:" + k);
        });
    }

    private String track(String key) {
        usedKeys.add(key);
        return key;
    }

    @Test
    void tokenBucketAllowsUpToCapacityThenRejects() {
        String key = track("tb-capacity");
        for (int i = 0; i < 3; i++) {
            RateLimitResult result = rateLimiter.tryAcquireTokenBucket(key, 3, FROZEN_RATE, 1);
            assertTrue(result.allowed(), "桶内令牌应放行, 第" + (i + 1) + "次");
        }
        assertEquals(0, rateLimiter.tryAcquireTokenBucket(key, 3, FROZEN_RATE, 1).remaining());

        RateLimitResult rejected = rateLimiter.tryAcquireTokenBucket(key, 3, FROZEN_RATE, 1);
        assertFalse(rejected.allowed(), "令牌耗尽应拒绝");
        assertEquals(0, rejected.remaining());
    }

    @Test
    void tokenBucketRefillsByElapsedTime() {
        String key = track("tb-refill");
        for (int i = 0; i < 3; i++) {
            assertTrue(rateLimiter.tryAcquireTokenBucket(key, 3, FROZEN_RATE, 1).allowed());
        }
        assertFalse(rateLimiter.tryAcquireTokenBucket(key, 3, FROZEN_RATE, 1).allowed());

        // 回拨上次补充时刻 2s：rate=5/s 应补 10 个（封顶 capacity=3）
        redisTemplate.opsForHash().put("lk:rl:tb:" + key, "last",
                String.valueOf(System.currentTimeMillis() - 2_000));

        RateLimitResult refilled = rateLimiter.tryAcquireTokenBucket(key, 3, 5, 1);
        assertTrue(refilled.allowed(), "时间流逝应补充令牌");
        assertEquals(2, refilled.remaining(), "补充封顶 capacity=3，取 1 剩 2");
    }

    @Test
    void tokenBucketRefillNeverExceedsCapacity() {
        String key = track("tb-cap");
        assertTrue(rateLimiter.tryAcquireTokenBucket(key, 2, FROZEN_RATE, 1).allowed());

        // 回拨 1 小时：可补充量远超容量，仍封顶
        redisTemplate.opsForHash().put("lk:rl:tb:" + key, "last",
                String.valueOf(System.currentTimeMillis() - 3_600_000));

        RateLimitResult result = rateLimiter.tryAcquireTokenBucket(key, 2, 100, 1);
        assertTrue(result.allowed());
        assertEquals(1, result.remaining(), "空闲补充封顶 capacity，不满溢");
    }

    @Test
    void tokenBucketConcurrentAcquireNeverOversells() throws Exception {
        String key = track("tb-concurrent");
        assertEquals(10, concurrentAllowedCount(
                () -> rateLimiter.tryAcquireTokenBucket(key, 10, FROZEN_RATE, 1).allowed()),
                "50 并发抢 capacity=10，放行应恰为 10（Lua 原子）");
    }

    @Test
    void slidingWindowRejectsBeyondThreshold() {
        String key = track("sw-threshold");
        for (int i = 0; i < 3; i++) {
            assertTrue(rateLimiter.tryAcquireSlidingWindow(key, 3, Duration.ofSeconds(60)).allowed());
        }
        assertFalse(rateLimiter.tryAcquireSlidingWindow(key, 3, Duration.ofSeconds(60)).allowed(),
                "窗口内超阈值应拒绝");
    }

    @Test
    void slidingWindowSlidesOutOfWindowMembersOut() {
        String key = track("sw-slide");
        long now = System.currentTimeMillis();
        // 窗外旧成员（60s 窗口）+ 两个窗内成员，模拟 2 次历史放行 + 1 次已滑出的放行
        redisTemplate.opsForZSet().add("lk:rl:sw:" + key, "stale-member", now - 61_000);
        redisTemplate.opsForZSet().add("lk:rl:sw:" + key, "recent-1", now - 1_000);
        redisTemplate.opsForZSet().add("lk:rl:sw:" + key, "recent-2", now - 500);

        RateLimitResult result = rateLimiter.tryAcquireSlidingWindow(key, 3, Duration.ofSeconds(60));
        assertTrue(result.allowed(), "窗外成员应被清除，窗口内仅 2 个");
        assertEquals(0, result.remaining());
        assertEquals(null, redisTemplate.opsForZSet().score("lk:rl:sw:" + key, "stale-member"),
                "窗外成员应被 ZREMRANGEBYSCORE 清除");
    }

    @Test
    void slidingWindowRejectedRequestDoesNotOccupyWindow() {
        String key = track("sw-reject");
        for (int i = 0; i < 3; i++) {
            assertTrue(rateLimiter.tryAcquireSlidingWindow(key, 3, Duration.ofSeconds(60)).allowed());
        }
        assertFalse(rateLimiter.tryAcquireSlidingWindow(key, 3, Duration.ofSeconds(60)).allowed());
        assertEquals(3, redisTemplate.opsForZSet().zCard("lk:rl:sw:" + key).longValue(),
                "拒绝的请求不得登记进窗口");
    }

    @Test
    void slidingWindowConcurrentAcquireNeverOversells() throws Exception {
        String key = track("sw-concurrent");
        assertEquals(10, concurrentAllowedCount(
                () -> rateLimiter.tryAcquireSlidingWindow(key, 10, Duration.ofSeconds(60)).allowed()),
                "50 并发抢 threshold=10，放行应恰为 10（Lua 原子）");
    }

    @Test
    void keysCarryFrameworkPrefixAndIdleTtl() {
        String key = track("prefix");
        assertTrue(rateLimiter.tryAcquireTokenBucket(key, 5, 1, 1).allowed());

        Long ttl = redisTemplate.getExpire("lk:rl:tb:" + key, TimeUnit.SECONDS);
        assertTrue(ttl != null && ttl > 0, "桶 key 应带空闲 TTL 自动清理");
    }

    private int concurrentAllowedCount(java.util.function.Supplier<Boolean> acquisition) throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (Boolean.TRUE.equals(acquisition.get())) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(15, TimeUnit.SECONDS);
        pool.shutdownNow();
        return allowed.get();
    }
}
