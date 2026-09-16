package com.localink.mq;

import com.localink.service.VoucherOrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 秒杀异步建单消费者：薄适配层，业务在 {@link VoucherOrderService#createSeckillOrder}。
 * 消费失败钩子后 rethrow 走重投（M3.7 纪律）；Redis 回滚属 M3.11（重试耗尽才回滚才正确）。
 */
@Component
public class SeckillOrderConsumer extends AbstractKafkaConsumer<SeckillOrderMessage> {

    private final VoucherOrderService voucherOrderService;

    public SeckillOrderConsumer(VoucherOrderService voucherOrderService) {
        super(SeckillOrderMessage.class);
        this.voucherOrderService = voucherOrderService;
    }

    @KafkaListener(topics = MqTopics.SECKILL_ORDER, groupId = "localink-server-seckill-order")
    void onMessage(String value, Acknowledgment ack) {
        dispatch(value, ack);
    }

    @Override
    protected void doConsume(SeckillOrderMessage payload, MessageEnvelope<SeckillOrderMessage> envelope) {
        voucherOrderService.createSeckillOrder(payload);
    }
}
