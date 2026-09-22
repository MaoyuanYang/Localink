package com.localink.ratelimit;

/**
 * 一次限流判定的结果：allowed 是否放行；remaining 判定后的剩余额度
 * （令牌桶=可用令牌数向下取整，滑动窗口=窗口内还能放行的请求数）。
 */
public record RateLimitResult(boolean allowed, long remaining) {

    public static RateLimitResult allowed(long remaining) {
        return new RateLimitResult(true, remaining);
    }

    public static RateLimitResult rejected() {
        return new RateLimitResult(false, 0);
    }
}
