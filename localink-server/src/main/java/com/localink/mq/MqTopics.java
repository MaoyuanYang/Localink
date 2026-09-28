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

    /**
     * 帖子→ES 同步（M6-D）：key=postId（同帖 UPSERT/DELETE 同分区 FIFO 防乱序），固定消费组
     * （单写语义），分区 3。消息只带 postId+事件类型，消费端查 DB 组装 upsert/删文档。
     */
    public static final String POST_SEARCH_SYNC = "post-search-sync";

    /**
     * 帖子异步审核（M6-F）：key=postId，固定消费组。发帖同步初筛通过后投递，
     * 消费端隐性词库复审——命中则驳回（audit=2）并复用 PostDeletedEvent 删 ES。
     */
    public static final String POST_AUDIT = "post-audit";
}
