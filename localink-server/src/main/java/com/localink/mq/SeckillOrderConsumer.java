package com.localink.mq;

import com.localink.config.SeckillConsumeProperties;
import com.localink.service.VoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 秒杀异步建单消费者：薄适配层，业务在 {@link VoucherOrderService#createSeckillOrder}。
 * 消费失败钩子后 rethrow → seckillErrorHandler 指数退避重投 → 耗尽走 Recoverer 回滚资格；
 * 超龄消息（队列积压超阈值）在闸门丢弃——回滚前查 DB 订单（源真相，防"建成了但 ack 丢失"被误回滚）。
 */
@Slf4j
@Component
public class SeckillOrderConsumer extends AbstractKafkaConsumer<SeckillOrderMessage> {

    private final VoucherOrderService voucherOrderService;
    private final SeckillConsumeProperties properties;

    public SeckillOrderConsumer(VoucherOrderService voucherOrderService,
                                SeckillConsumeProperties properties) {
        super(SeckillOrderMessage.class);
        this.voucherOrderService = voucherOrderService;
        this.properties = properties;
    }

    @KafkaListener(
            topics = MqTopics.SECKILL_ORDER,
            groupId = "localink-server-seckill-order",
            containerFactory = "seckillContainerFactory")
    void onMessage(String value, Acknowledgment ack) {
        dispatch(value, ack);
    }

    @Override
    protected boolean beforeConsume(SeckillOrderMessage payload, MessageEnvelope<SeckillOrderMessage> envelope) {
        long ageMs = System.currentTimeMillis() - envelope.getTimestamp();
        if (envelope.getTimestamp() <= 0 || ageMs <= properties.getMaxStaleMs()) {
            return true;
        }
        if (voucherOrderService.seckillOrderExists(payload.orderId())) {
            log.warn("超龄消息但订单已落库，跳过不回滚, orderId={}, ageMs={}", payload.orderId(), ageMs);
            return false;
        }
        log.warn("超龄消息丢弃并回滚资格, orderId={}, ageMs={}", payload.orderId(), ageMs);
        voucherOrderService.rollbackSeckillQualification(payload.voucherId(), payload.userId(),
                "STALE_DROP", "stale message dropped, ageMs=" + ageMs);
        return false;
    }

    @Override
    protected void doConsume(SeckillOrderMessage payload, MessageEnvelope<SeckillOrderMessage> envelope) {
        voucherOrderService.createSeckillOrder(payload);
    }
}
