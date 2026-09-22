package com.localink.ratelimit.user;

/**
 * 用户维度解析 SPI：starter 无法感知宿主应用的会话体系（依赖纪律：不依赖 server），
 * 由宿主提供"当前请求的用户标识"（如 UserHolder 的 userId）。返回 null 表示匿名/未登录，
 * 该维度跳过（匿名流量由 IP 维度兜底）。
 */
public interface RateLimitUserResolver {

    /**
     * 当前线程请求的用户标识（作为限流 key 的一段）；匿名返回 null。
     */
    String resolveUser();
}
