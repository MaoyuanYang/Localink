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
}
