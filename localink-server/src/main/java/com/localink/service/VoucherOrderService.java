package com.localink.service;

import com.localink.mq.SeckillOrderMessage;

public interface VoucherOrderService {

    /**
     * 秒杀下单（异步版）：校验 → Lua 原子扣减+流水 → 发 Kafka 消息 → 返回预生成订单 ID。
     * 建单由 {@link #createSeckillOrder(SeckillOrderMessage, String)} 在消费端异步完成。
     */
    String seckill(Long voucherId);

    /**
     * 消费端异步建单（CAS 扣减 + 落库 + 对账流水行，同事务；orderId 幂等守卫）。
     *
     * @param messageId 消息 UUID（写入流水行，对账时关联消费幂等）
     */
    void createSeckillOrder(SeckillOrderMessage message, String messageId);

    /**
     * 统一回滚入口：逆增量 Lua 退还资格（库存 +1、用户出集合、流水翻恢复态，幂等）+ 落 DB 恢复流水行；
     * 执行失败落回滚失败表（M5.2 补偿钩子）。
     *
     * @param traceId 资格生命周期 ID（与扣减流水同源；可空=只退资格不翻流水）
     * @param source  来源组件（REQUEST_SEND / CONSUME_EXHAUSTED / STALE_DROP）
     */
    void rollbackSeckillQualification(Long voucherId, Long userId, Long orderId, Long traceId,
                                      String source, String detail);

    /**
     * 订单是否已落库（超龄丢弃/耗尽回滚前的源真相判据——幂等标记允许写失败降级，不可作判据）。
     */
    boolean seckillOrderExists(Long orderId);
}
