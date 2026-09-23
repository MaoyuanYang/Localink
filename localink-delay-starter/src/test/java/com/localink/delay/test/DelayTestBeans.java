package com.localink.delay.test;

import com.localink.delay.DelayQueueConsumer;
import com.localink.delay.DelayQueuePublisher;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试靶子：topic-a 正常消费记录 payload；topic-b 前 N 次抛异常（验证框架重投）。
 */
class DelayTestBeans {

    @Component
    static class RecordingConsumer extends DelayQueueConsumer {

        final List<String> received = new CopyOnWriteArrayList<>();

        RecordingConsumer(RedissonClient redissonClient, DelayQueuePublisher publisher,
                          @Value("${localink.delay.shards:2}") int shards) {
            super(redissonClient, publisher, "topic-a", shards);
        }

        @Override
        protected void doConsume(String payload) {
            received.add(payload);
        }
    }

    @Component
    static class FlakyConsumer extends DelayQueueConsumer {

        final List<String> received = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();

        FlakyConsumer(RedissonClient redissonClient, DelayQueuePublisher publisher,
                      @Value("${localink.delay.shards:2}") int shards) {
            super(redissonClient, publisher, "topic-b", shards);
        }

        @Override
        protected void doConsume(String payload) {
            if (attempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("planned failure #" + attempts.get());
            }
            received.add(payload);
        }
    }
}
