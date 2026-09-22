package com.localink.ratelimit;

/**
 * 限流算法：TOKEN_BUCKET（突发友好+平均速率）/ SLIDING_WINDOW（严格均匀）。
 * 场景配置必填——场景选型语义见任务卡 m3-13。
 */
public enum Algorithm {

    TOKEN_BUCKET,

    SLIDING_WINDOW
}
