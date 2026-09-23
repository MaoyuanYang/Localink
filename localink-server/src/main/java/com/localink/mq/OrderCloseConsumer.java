package com.localink.mq;

import com.localink.delay.DelayQueueConsumer;
import com.localink.delay.DelayQueuePublisher;
import com.localink.service.VoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 订单超时关单消费者（M5-B）：薄适配层，业务在 {@link VoucherOrderService#closeOrderIfExpired}。
 * 消费失败由框架重投（最多 3 次）；业务自身幂等（条件关单闸门），重投空转无副作用。
 */
@Slf4j
@Component
public class OrderCloseConsumer extends DelayQueueConsumer {

    private final VoucherOrderService voucherOrderService;

    public OrderCloseConsumer(RedissonClient redissonClient,
                              DelayQueuePublisher delayQueuePublisher,
                              VoucherOrderService voucherOrderService,
                              @Value("${localink.delay.shards:2}") int shards) {
        super(redissonClient, delayQueuePublisher, DelayTopics.ORDER_CLOSE, shards);
        this.voucherOrderService = voucherOrderService;
    }

    @Override
    protected void doConsume(String payload) {
        Long orderId = Long.valueOf(payload);
        boolean closed = voucherOrderService.closeOrderIfExpired(orderId);
        if (closed) {
            log.info("订单超时关闭完成, orderId={}", orderId);
        }
    }
}
