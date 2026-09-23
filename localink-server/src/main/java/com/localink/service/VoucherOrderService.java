package com.localink.service;

import com.localink.mq.SeckillOrderMessage;

public interface VoucherOrderService {

    /**
     * 秒杀下单（异步版，令牌前置入口 M3.15）：一次性消费前置令牌 → 校验 → Lua 原子扣减+流水
     * → 发 Kafka 消息 → 返回预生成订单 ID。Controller 层唯一入口。
     *
     * @param token 申请接口发放的一次性令牌
     */
    String seckill(Long voucherId, String token);

    /**
     * 秒杀下单（异步版）：校验 → Lua 原子扣减+流水 → 发 Kafka 消息 → 返回预生成订单 ID。
     * 令牌闸门之前的内部入口（测试与补偿链路直调，外部请求一律走 {@link #seckill(Long, String)}）。
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

    /**
     * M5-B 超时关单（延迟任务到期触发）：条件关单（仅"已创建"可关，幂等闸门）
     * → DB 库存逆增量回补 → Redis 资格回滚（复用统一回滚：库存+1/出集合/流水翻恢复，失败落失败表）。
     *
     * @return true 本次实际关闭并回流；false 已关/已取消/不存在（幂等跳过）
     */
    boolean closeOrderIfExpired(Long orderId);

    /**
     * M4.5 反查订单位置：路由表定位物理库表；路由缺失（跨库写缝隙）位置字段为 null、广播兜底存在性。
     */
    OrderLocation locateOrder(Long orderId);

    /**
     * 订单物理位置（分片反查结果）。
     */
    record OrderLocation(Long orderId, Long userId, Long voucherId,
                         String dataSource, String physicalTable, boolean orderExists) {
    }
}
