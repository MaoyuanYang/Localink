package com.localink.mq;

/**
 * 延迟队列名登记处（baseQueue 段，实际物理队列 lk:delay:{base}:{shard}，
 * 见 delay-starter 与 KeyManage.DELAY_QUEUE 登记）。
 */
public final class DelayTopics {

    private DelayTopics() {
    }

    /**
     * 订单超时关单：payload=orderId，shardKey=orderId，到期触发条件关单与库存回流（M5-B）。
     */
    public static final String ORDER_CLOSE = "order-close";

    /**
     * 开抢预通知：payload=voucherId，shardKey=voucherId，beginTime−lead 到期圈名单群发（M5-C）。
     */
    public static final String SECKILL_NOTICE = "seckill-notice";
}
