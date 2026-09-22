package com.localink.ratelimit;

/**
 * 限流维度：IP（匿名可判，挡代理切换账号的刷子）/ USER（登录身份，挡单账号脚本）。
 * 双维度并存时任一超限即拒绝——key 各自独立计数。
 */
public enum Dimension {

    IP,

    USER
}
