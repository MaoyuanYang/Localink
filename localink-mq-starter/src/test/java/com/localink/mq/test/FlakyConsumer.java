package com.localink.mq.test;

import com.localink.mq.AbstractKafkaConsumer;
import com.localink.mq.MessageEnvelope;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 前两次消费抛异常（不 ack → 容器重投），第三次成功。验证重投纪律与失败钩子。
 */
@Component
class FlakyConsumer extends AbstractKafkaConsumer<TestPayload> {

    final AtomicInteger attempts = new AtomicInteger();
    final AtomicInteger failureHooks = new AtomicInteger();
    final List<TestPayload> received = new CopyOnWriteArrayList<>();
    final CountDownLatch successLatch = new CountDownLatch(1);

    FlakyConsumer() {
        super(TestPayload.class);
    }

    @KafkaListener(topics = "${m37.topics.flaky}", groupId = "m37-flaky-${random.uuid}")
    void onMessage(String value, Acknowledgment ack) {
        dispatch(value, ack);
    }

    @Override
    protected void doConsume(TestPayload payload, MessageEnvelope<TestPayload> envelope) {
        if (attempts.incrementAndGet() <= 2) {
            throw new IllegalStateException("boom-" + attempts.get());
        }
        received.add(payload);
        successLatch.countDown();
    }

    @Override
    protected void afterConsumeFailure(TestPayload payload, MessageEnvelope<TestPayload> envelope, Throwable t) {
        failureHooks.incrementAndGet();
    }
}
