package com.localink.mq;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.localink.service.VoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import java.lang.reflect.Type;

/**
 * 重试耗尽钩子（M3.11 补偿时机学的兑现）：立即回滚+重投会超卖，回滚必须等重试耗尽。
 * 订单已落库（成功过、ack 丢失）则只记日志；未落库则回滚资格；回滚自身失败由统一入口落失败表。
 * Recoverer 正常返回即视为"已处理"，位移提交，不进毒消息循环。
 */
@Slf4j
public class SeckillOrderRecoverer implements ConsumerRecordRecoverer {

    private static final Type ENVELOPE_TYPE = new TypeReference<MessageEnvelope<Object>>() {
    }.getType();

    private final VoucherOrderService voucherOrderService;

    public SeckillOrderRecoverer(VoucherOrderService voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        SeckillOrderMessage payload = parse(record.value());
        if (payload == null) {
            log.error("重试耗尽且消息无法解析, 原文丢弃, value={}", record.value(), exception);
            return;
        }
        if (voucherOrderService.seckillOrderExists(payload.orderId())) {
            log.warn("重试耗尽但订单已落库（建单成功、ack 丢失场景），无需回滚, orderId={}", payload.orderId());
            return;
        }
        log.error("重试耗尽，回滚秒杀资格, orderId={}, voucherId={}, userId={}, reason={}",
                payload.orderId(), payload.voucherId(), payload.userId(), exception.getMessage(), exception);
        voucherOrderService.rollbackSeckillQualification(payload.voucherId(), payload.userId(),
                "CONSUME_EXHAUSTED", "retries exhausted: " + exception.getMessage());
    }

    @SuppressWarnings("unchecked")
    private SeckillOrderMessage parse(Object value) {
        try {
            MessageEnvelope<Object> envelope = JSON.parseObject((String) value, ENVELOPE_TYPE);
            if (envelope == null || envelope.getBody() == null) {
                return null;
            }
            return JSON.to(SeckillOrderMessage.class, envelope.getBody());
        } catch (Exception e) {
            return null;
        }
    }
}
