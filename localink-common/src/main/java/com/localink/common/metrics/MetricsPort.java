package com.localink.common.metrics;

/**
 * 指标上报端口（common 定义、server 实现）：starter 模块经 ObjectProvider 可选注入，
 * 无实现（纯 starter 自测）时静默降级为空操作。PRD §5 可观测承诺（回滚/限流/对账指标
 * 暴露到 /actuator/metrics）的落地通道。
 */
public interface MetricsPort {

    MetricsPort NOOP = new MetricsPort() {
        @Override
        public void increment(String name, String... tags) {
        }
    };

    /**
     * 计数器自增；tags 成对出现（key1, value1, key2, value2...）。
     */
    void increment(String name, String... tags);
}
