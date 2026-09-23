package com.localink.service.impl;

import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.cache.model.ZSetEntry;
import com.localink.constant.KeyManage;
import com.localink.entity.Voucher;
import com.localink.id.SnowflakeIdGenerator;
import com.localink.mapper.VoucherMapper;
import com.localink.mq.MessageProducer;
import com.localink.mq.MqTopics;
import com.localink.mq.SeckillOrderMessage;
import com.localink.service.SubscribeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 订阅通知实现（M5-C）：ZSet 排队 + Hash 状态（database.md 第 7 节既定设计）。
 *
 * <p>自动发券=替最早订阅者走异步建单：发 Kafka 消息即继承消费端全套（幂等/流水/路由/超时关单）。
 * 补位单若再超时关单 → 回流 → 弹下一位——链式补位是正确语义而非递归缺陷。
 * 声明：发券走 DB 扣减不扣 Redis 预热值，Redis 库存短暂显示+1——由自然扣减对齐/活动 TTL/
 * 对账回灌的既有口径兜底（任务卡）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscribeServiceImpl implements SubscribeService {

    private static final String STATUS_SUBSCRIBED = "SUBSCRIBED";
    private static final String STATUS_GRANTED = "GRANTED";
    private static final int TYPE_SECKILL = 2;

    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;
    private final SnowflakeIdGenerator idGenerator;
    private final MessageProducer messageProducer;
    private final VoucherMapper voucherMapper;

    @Override
    public void subscribe(Long voucherId, Long userId) {
        if (redisCache.zsets().score(queueKey(voucherId), String.valueOf(userId)) == null) {
            redisCache.zsets().add(queueKey(voucherId), String.valueOf(userId),
                    System.currentTimeMillis());
        }
        redisCache.hashes().put(statusKey(voucherId), String.valueOf(userId), STATUS_SUBSCRIBED);
    }

    @Override
    public void unsubscribe(Long voucherId, Long userId) {
        redisCache.zsets().remove(queueKey(voucherId), String.valueOf(userId));
        redisCache.hashes().delete(statusKey(voucherId), String.valueOf(userId));
    }

    @Override
    public String status(Long voucherId, Long userId) {
        return redisCache.hashes().get(statusKey(voucherId), String.valueOf(userId), String.class);
    }

    @Override
    public void tryGrantEarliest(Long voucherId) {
        ZSetEntry<String> earliest = redisCache.zsets().popMin(queueKey(voucherId), String.class);
        if (earliest == null) {
            return;
        }
        Long userId = Long.valueOf(earliest.value());
        Voucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null || voucher.getType() == null || voucher.getType() != TYPE_SECKILL) {
            log.warn("自动发券跳过：券不存在或非秒杀券, voucherId={}, userId={}", voucherId, userId);
            return;
        }
        long orderId = idGenerator.nextId();
        messageProducer.sendSync(MqTopics.SECKILL_ORDER, String.valueOf(voucherId),
                new SeckillOrderMessage(orderId, voucherId, voucher.getType(), userId, null, null, null));
        redisCache.hashes().put(statusKey(voucherId), String.valueOf(userId), STATUS_GRANTED);
        log.info("回流自动发券: 最早订阅者获得补位, voucherId={}, userId={}, orderId={}", voucherId, userId, orderId);
    }

    private com.localink.cache.KeyBuild queueKey(Long voucherId) {
        return keyBuilder.build(KeyManage.SUBSCRIBE_QUEUE, voucherId);
    }

    private com.localink.cache.KeyBuild statusKey(Long voucherId) {
        return keyBuilder.build(KeyManage.SUBSCRIBE_STATUS, voucherId);
    }
}
