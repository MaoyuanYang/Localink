package com.localink.mq;

import com.alibaba.fastjson2.JSON;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

/**
 * 生产者门面：自动封装 MessageEnvelope 并以 String 传输。可靠性由应用配置保证
 * （acks=all + enable.idempotence，见 server application.yml），本类只负责封装与日志纪律。
 */
@Slf4j
public class MessageProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public MessageProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 异步发送（业务载荷自动封包）。返回 future 供调用方编排补偿（如 M3.8 发送失败回滚 Redis）。
     */
    public CompletableFuture<SendResult<String, String>> sendAsync(String topic, Object payload) {
        return sendAsync(topic, MessageEnvelope.of(payload));
    }

    /**
     * 异步发送（指定分区路由 key，同 key 有序）。
     */
    public CompletableFuture<SendResult<String, String>> sendAsync(String topic, String key, Object payload) {
        return sendAsync(topic, MessageEnvelope.of(payload, key));
    }

    /**
     * 同步发送：阻塞到 broker 确认（acks=all 下即 ISR 落盘），用于不能丢的关键消息与测试。
     * 失败抛 MQ_SEND_FAILED。
     */
    public SendResult<String, String> sendSync(String topic, Object payload) {
        return join(topic, sendAsync(topic, payload));
    }

    /**
     * 同步发送（指定 key）。
     */
    public SendResult<String, String> sendSync(String topic, String key, Object payload) {
        return join(topic, sendAsync(topic, key, payload));
    }

    private CompletableFuture<SendResult<String, String>> sendAsync(String topic, MessageEnvelope<?> envelope) {
        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(topic, envelope.getKey(), JSON.toJSONString(envelope));
        future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                log.error("Kafka 发送失败, topic={}, messageId={}", topic, envelope.getMessageId(), throwable);
            } else {
                RecordMetadata meta = result.getRecordMetadata();
                log.debug("Kafka 发送成功, topic={}, partition={}, offset={}, messageId={}",
                        topic, meta.partition(), meta.offset(), envelope.getMessageId());
            }
        });
        return future;
    }

    private SendResult<String, String> join(String topic, CompletableFuture<SendResult<String, String>> future) {
        try {
            return future.join();
        } catch (Exception e) {
            throw new LocalinkException(BaseCode.MQ_SEND_FAILED, "topic=" + topic + ": " + e.getMessage());
        }
    }
}
