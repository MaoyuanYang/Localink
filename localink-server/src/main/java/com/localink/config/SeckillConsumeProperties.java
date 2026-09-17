package com.localink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 秒杀消费端可靠性参数：超龄阈值与指数退避全部可调（压测放宽用，不写死）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.seckill.consume")
public class SeckillConsumeProperties {

    /**
     * 消息超龄阈值（毫秒）：入队到此时刻超过它的建单消息视为过期，回滚资格后丢弃。
     */
    private long maxStaleMs = 10_000;

    /**
     * 总尝试次数（含首次）：1 + 重试次数。
     */
    private int maxAttempts = 4;

    /**
     * 退避初始间隔（毫秒）。
     */
    private long backoffInitialMs = 200;

    /**
     * 退避倍率。
     */
    private double backoffMultiplier = 2.0;

    /**
     * 退避封顶间隔（毫秒）。
     */
    private long backoffMaxMs = 1000;
}
