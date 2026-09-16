package com.localink.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * 统一消息封装：业务载荷 + 消息元数据。messageId 供消费幂等去重（M3.10），
 * timestamp 供延迟消息超龄丢弃（M3.11）。value 一律 fastjson2 序列化为 String 传输。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageEnvelope<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息唯一 ID（生产侧自动生成，消费侧幂等键）。
     */
    private String messageId;

    /**
     * 分区路由 key（可空；空时轮询分区）。
     */
    private String key;

    /**
     * 业务自定义头（可空）。
     */
    private Map<String, String> headers;

    /**
     * 生产时间戳（epoch 毫秒）。
     */
    private long timestamp;

    /**
     * 业务载荷。
     */
    private T body;

    public static <T> MessageEnvelope<T> of(T body) {
        return new MessageEnvelope<>(UUID.randomUUID().toString(), null, null, System.currentTimeMillis(), body);
    }

    public static <T> MessageEnvelope<T> of(T body, String key) {
        return new MessageEnvelope<>(UUID.randomUUID().toString(), key, null, System.currentTimeMillis(), body);
    }
}
