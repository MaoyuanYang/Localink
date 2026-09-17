package com.localink.mq;

/**
 * Kafka topic 常量登记处（与 application.yml 的 localink.mq.topics 镜像，枚举即文档）。
 */
public final class MqTopics {

    private MqTopics() {
    }

    /**
     * 秒杀异步建单：key=voucherId（同券同分区局部有序），分区 3。
     */
    public static final String SECKILL_ORDER = "seckill-order";

    /**
     * 本地缓存失效广播：消费组每实例独立（random.uuid）→ 组间广播；消息轻量无序需求，分区 1。
     */
    public static final String CACHE_INVALIDATION = "cache-invalidation";
}
