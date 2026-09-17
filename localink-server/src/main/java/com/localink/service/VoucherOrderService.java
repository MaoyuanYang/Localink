package com.localink.service;

import com.localink.mq.SeckillOrderMessage;

public interface VoucherOrderService {

    /**
     * 秒杀下单（异步版）：校验 → Lua 原子扣减 → 发 Kafka 消息 → 返回预生成订单 ID。
     * 建单由 {@link #createSeckillOrder(SeckillOrderMessage)} 在消费端异步完成。
     */
    String seckill(Long voucherId);

    /**
     * 消费端异步建单（CAS 扣减 + 落库；orderId 幂等守卫）。
     */
    void createSeckillOrder(SeckillOrderMessage message);

    /**
     * 统一回滚入口：逆增量 Lua 退还资格（库存 +1、用户出集合，幂等）；执行失败落回滚失败表（M5.2 补偿钩子）。
     *
     * @param source 来源组件（REQUEST_SEND / CONSUME_EXHAUSTED / STALE_DROP）
     */
    void rollbackSeckillQualification(Long voucherId, Long userId, String source, String detail);

    /**
     * 订单是否已落库（超龄丢弃/耗尽回滚前的源真相判据——幂等标记允许写失败降级，不可作判据）。
     */
    boolean seckillOrderExists(Long orderId);
}
