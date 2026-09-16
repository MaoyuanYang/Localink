package com.localink.mq.test;

import com.localink.mq.MessageProducer;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每次运行注入随机 topic（配合随机消费组 + earliest），隔离 Kafka 卷上历史消息，
 * 使计数断言不受多次运行影响。
 */
@SpringBootTest
class MessageRoundTripIntegrationTest {

    private static final String RT_TOPIC = "m37-rt-" + UUID.randomUUID();
    private static final String FLAKY_TOPIC = "m37-flaky-" + UUID.randomUUID();
    private static final String GATE_TOPIC = "m37-gate-" + UUID.randomUUID();

    @DynamicPropertySource
    static void topics(DynamicPropertyRegistry registry) {
        registry.add("m37.topics.rt", () -> RT_TOPIC);
        registry.add("m37.topics.flaky", () -> FLAKY_TOPIC);
        registry.add("m37.topics.gate", () -> GATE_TOPIC);
    }

    @Autowired
    private MessageProducer messageProducer;

    @Autowired
    private RoundTripConsumer roundTripConsumer;

    @Autowired
    private FlakyConsumer flakyConsumer;

    @Autowired
    private GateConsumer gateConsumer;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Test
    void sendSyncRoundTripsPayloadAndMeta() throws Exception {
        TestPayload payload = new TestPayload("alice", 42L);

        SendResult<String, String> result = messageProducer.sendSync(RT_TOPIC, "route-key-1", payload);

        assertNotNull(result.getRecordMetadata().offset());
        assertTrue(roundTripConsumer.latch.await(15, TimeUnit.SECONDS), "往返消息应在超时前被消费");
        assertEquals(payload, roundTripConsumer.received.get(0));
        var envelope = roundTripConsumer.envelopes.get(0);
        assertNotNull(envelope.getMessageId());
        assertTrue(envelope.getTimestamp() > 0);
        assertEquals("route-key-1", envelope.getKey());
    }

    @Test
    void failedConsumeIsRedeliveredUntilSuccess() throws Exception {
        messageProducer.sendAsync(FLAKY_TOPIC, new TestPayload("bob", 1L));

        assertTrue(flakyConsumer.successLatch.await(20, TimeUnit.SECONDS), "重投后应最终消费成功");
        assertEquals(3, flakyConsumer.attempts.get(), "默认错误处理器应重投失败消息");
        assertEquals(2, flakyConsumer.failureHooks.get(), "每次失败都应触发失败钩子");
        assertEquals(1, flakyConsumer.received.size());
    }

    @Test
    void gateSkipsMessageWithoutReachingBusiness() throws Exception {
        messageProducer.sendAsync(GATE_TOPIC, new TestPayload("skip", 7L));
        messageProducer.sendAsync(GATE_TOPIC, new TestPayload("pass", 8L));

        assertTrue(gateConsumer.passLatch.await(15, TimeUnit.SECONDS), "放行消息应被正常消费");
        assertEquals(1, gateConsumer.received.size());
        assertEquals(1, gateConsumer.skipped.size());
        assertEquals("skip", gateConsumer.skipped.get(0).name());
    }

    @Test
    void configuredTopicIsCreatedByKafkaAdmin() throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                environment.getProperty("spring.kafka.bootstrap-servers", "localhost:9092")))) {
            TopicDescription description = admin.describeTopics(java.util.List.of("m37-static"))
                    .allTopicNames()
                    .get(15, TimeUnit.SECONDS)
                    .get("m37-static");
            assertEquals(2, description.partitions().size(), "分区数应以 localink.mq.topics 配置为准（broker 默认 3）");
        }
    }
}
