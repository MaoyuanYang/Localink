package com.localink.mq;

/**
 * 秒杀建单消息：Lua 扣减成功后由请求线程发出，消费端据此建单。
 * orderId 由生产侧预生成（雪花），API 即时返回同一 ID——响应契约与同步版一致。
 * M3.12 增加对账账目：traceId（资格生命周期 ID，与 Redis 流水 field 同源）、
 * stockBefore/stockAfter（Lua 返回的扣减前后库存），消费端随建单事务落 lk_voucher_reconcile_log。
 */
public record SeckillOrderMessage(Long orderId, Long voucherId, Integer voucherType, Long userId,
                                  Long traceId, Integer stockBefore, Integer stockAfter) {
}
