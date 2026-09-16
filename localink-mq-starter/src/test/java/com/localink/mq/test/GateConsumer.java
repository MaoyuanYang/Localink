package com.localink.mq.test;

import com.localink.mq.AbstractKafkaConsumer;
import com.localink.mq.MessageEnvelope;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * 前置闸门：name 为 "skip" 的载荷直接跳过（ack 不投递业务），其余放行。验证 beforeConsume 语义。
 */
@Component
class GateConsumer extends AbstractKafkaConsumer<TestPayload> {

    final List<TestPayload> received = new CopyOnWriteArrayList<>();
    final List<TestPayload> skipped = new CopyOnWriteArrayList<>();
    final CountDownLatch passLatch = new CountDownLatch(1);

    GateConsumer() {
        super(TestPayload.class);
    }

    @KafkaListener(topics = "${m37.topics.gate}", groupId = "m37-gate-${random.uuid}")
    void onMessage(String value, Acknowledgment ack) {
        dispatch(value, ack);
    }

    @Override
    protected boolean beforeConsume(TestPayload payload, MessageEnvelope<TestPayload> envelope) {
        if ("skip".equals(payload.name())) {
            skipped.add(payload);
            return false;
        }
        return true;
    }

    @Override
    protected void doConsume(TestPayload payload, MessageEnvelope<TestPayload> envelope) {
        received.add(payload);
        passLatch.countDown();
    }
}
