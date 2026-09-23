package com.localink.delay.test;

import com.localink.delay.DelayQueuePublisher;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5-B 延迟队列框架验证：到期送达、分片路由稳定、失败重投（信封计数）。
 */
@SpringBootTest
class DelayQueueIntegrationTest {

    @Autowired
    private DelayQueuePublisher publisher;

    @Autowired
    private DelayTestBeans.RecordingConsumer recordingConsumer;

    @Autowired
    private DelayTestBeans.FlakyConsumer flakyConsumer;

    @Autowired
    private RedissonClient redissonClient;

    @AfterEach
    void cleanup() {
        redissonClient.getKeys().deleteByPattern("lk:delay:topic-*");
    }

    @Test
    void messageArrivesAfterDelay() {
        publisher.offerSharded("topic-a", "k1", "hello-delay", Duration.ofMillis(600));

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> recordingConsumer.received.contains("hello-delay"));
    }

    @Test
    void shardedKeysRouteStablyAcrossQueues() {
        // 路由纯函数断言：同 key 恒定同分片；两个 key 覆盖两个分片（shards=2）
        Set<String> queues = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            queues.add(publisher.queueName("topic-a", "key-" + i));
        }
        assertEquals(2, queues.size(), "20 个 key 应散满 2 个分片队列: " + queues);
        assertEquals(publisher.queueName("topic-a", "same-key"), publisher.queueName("topic-a", "same-key"),
                "同 key 路由稳定");

        publisher.offerSharded("topic-a", "k2", "shard-a", Duration.ofMillis(200));
        publisher.offerSharded("topic-a", "k3", "shard-b", Duration.ofMillis(200));
        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> recordingConsumer.received.containsAll(java.util.List.of("shard-a", "shard-b")));
    }

    @Test
    void failedConsumptionIsRetriedAndEventuallySucceeds() {
        publisher.offerSharded("topic-b", "k4", "retry-me", Duration.ofMillis(200));

        Awaitility.await().atMost(Duration.ofSeconds(8))
                .until(() -> flakyConsumer.received.contains("retry-me"));
        assertTrue(flakyConsumer.attempts.get() >= 3, "前两次失败应触发重投, 实际尝试=" + flakyConsumer.attempts.get());
    }
}
