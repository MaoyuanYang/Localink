package com.localink.service;

/**
 * 秒杀券订阅（M5-C）：售罄后订阅排队，订单回流（取消/超时关单）时自动发券给最早订阅者。
 */
public interface SubscribeService {

    /**
     * 订阅：进 ZSet 排队（score=订阅时刻）+ 状态置 SUBSCRIBED。
     * 重复订阅保持首刻 score（排队位置不变——"最早"按首次订阅计）。
     */
    void subscribe(Long voucherId, Long userId);

    /**
     * 取消订阅：出队 + 清状态。
     */
    void unsubscribe(Long voucherId, Long userId);

    /**
     * 订阅状态：SUBSCRIBED / GRANTED / null（未订阅）。
     */
    String status(Long voucherId, Long userId);

    /**
     * 回流自动发券（订单回流成功后调用）：popMin 原子弹最早订阅者 →
     * 以其名义发建单消息（复用异步建单全套：DB 扣减+流水+路由+超时关单）→ 状态置 GRANTED。
     * 无订阅者静默返回（库存留给后来者自然抢）。
     */
    void tryGrantEarliest(Long voucherId);
}
