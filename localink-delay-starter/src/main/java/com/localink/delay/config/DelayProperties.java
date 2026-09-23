package com.localink.delay.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 延迟队列配置（M5-B）：分片数与队列名前缀。分片数变更会使在途任务路由漂移——
 * 只允许停机窗口调整（教学口径，任务卡有声明）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.delay")
public class DelayProperties {

    /**
     * 队列统一前缀（Redisson 命名空间，非 KeyBuilder 治理——登记在 KeyManage 作文档对齐）。
     */
    private String keyPrefix = "lk:";

    /**
     * 分片数：每个 baseQueue 拆成 N 个物理队列，各一个消费线程。
     */
    private int shards = 2;
}
