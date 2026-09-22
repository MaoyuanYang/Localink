package com.localink.ratelimit;

import java.time.Duration;

/**
 * 限流原语门面（M3.13）：两个 Lua 原子原语，供 M3.14 RateLimitHandler 按场景/维度组装。
 * key 为业务语义键（如 "seckill-order:1001"），实现内统一加框架前缀（lk:rl:tb: / lk:rl:sw:）。
 * 时间由调用侧时钟传入（Lua 内取时间属非确定性命令，复制语义下有坑）。
 */
public interface RateLimiter {

    /**
     * 令牌桶：按时间差惰性补充令牌（elapsed × rate，封顶 capacity），够则扣减放行。
     * 突发友好——空闲攒下的额度可一次消耗；平均速率受 rate 约束。
     *
     * @param key         业务语义键（scene:dimension）
     * @param capacity    桶容量（最大突发额度）
     * @param ratePerSec  补充速率（个/秒，可小于 1）
     * @param permits     本次申请令牌数
     */
    RateLimitResult tryAcquireTokenBucket(String key, int capacity, double ratePerSec, int permits);

    /**
     * 滑动窗口：ZSet 记窗口内每次放行（member=唯一标识，score=毫秒），先清窗外再计数。
     * 严格均匀——任意 window 长度内放行数不超 threshold，无突发透支。
     *
     * @param key       业务语义键（scene:dimension）
     * @param threshold 窗口内最大放行数
     * @param window    窗口长度
     */
    RateLimitResult tryAcquireSlidingWindow(String key, int threshold, Duration window);
}
