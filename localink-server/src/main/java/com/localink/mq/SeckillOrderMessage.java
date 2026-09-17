package com.localink.mq;

/**
 * 秒杀建单消息：Lua 扣减成功后由请求线程发出，消费端据此建单。
 * orderId 由生产侧预生成（雪花），API 即时返回同一 ID——响应契约与同步版一致。
 */
public record SeckillOrderMessage(Long orderId, Long voucherId, Integer voucherType, Long userId) {
}
