package com.localink.mq;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.Acknowledgment;

import java.lang.reflect.Type;

/**
 * 消费者模板：子类自声明 @KafkaListener（topic/groupId/concurrency 自主），监听方法调用
 * {@link #dispatch} 获得统一纪律——解析 → 幂等闸门 → 业务 → 成功才手动 ack；异常走失败钩子且不 ack，
 * 由容器错误处理器重投（M3.11 在此基础上定制退避与死信）。
 *
 * <pre>
 * &#64;KafkaListener(topics = "...", groupId = "...")
 * public void onMessage(String value, Acknowledgment ack) {
 *     dispatch(value, ack);
 * }
 * </pre>
 *
 * @param <T> 业务载荷类型
 */
@Slf4j
public abstract class AbstractKafkaConsumer<T> {

    private final Class<T> payloadType;

    protected AbstractKafkaConsumer(Class<T> payloadType) {
        this.payloadType = payloadType;
    }

    /**
     * 子类监听方法统一委托入口。失败钩子执行后必须把异常继续抛出：
     * 容器错误处理器（seek 重投、M3.11 的退避/死信）依赖异常触发，吞掉等于确认消费。
     */
    public final void dispatch(String value, Acknowledgment ack) {
        MessageEnvelope<T> envelope = parse(value);
        T payload = envelope.getBody();
        try {
            if (!beforeConsume(payload, envelope)) {
                ack.acknowledge();
                log.info("消费前置闸门跳过消息, messageId={}", envelope.getMessageId());
                return;
            }
            doConsume(payload, envelope);
        } catch (Throwable t) {
            invokeFailureHookQuietly(payload, envelope, t);
            throw t;
        }
        ack.acknowledge();
    }

    private void invokeFailureHookQuietly(T payload, MessageEnvelope<T> envelope, Throwable cause) {
        try {
            afterConsumeFailure(payload, envelope, cause);
        } catch (Throwable hookError) {
            hookError.addSuppressed(cause);
            log.error("消费失败钩子自身抛错, messageId={}", envelope.getMessageId(), hookError);
        }
    }

    /**
     * 业务处理（子类实现）。
     */
    protected abstract void doConsume(T payload, MessageEnvelope<T> envelope);

    /**
     * 前置闸门：返回 false 跳过本消息（ack 后不再投递）。幂等去重（M3.10）、延迟超龄丢弃（M3.11）的挂点。
     */
    protected boolean beforeConsume(T payload, MessageEnvelope<T> envelope) {
        return true;
    }

    /**
     * 失败钩子：记录/回滚（M3.11 回滚 Redis 的挂点）。抛出与否不影响"不 ack 等重投"的纪律。
     */
    protected void afterConsumeFailure(T payload, MessageEnvelope<T> envelope, Throwable t) {
    }

    /**
     * 泛型基类里 TypeReference 拿不到 T 的实参（擦除），先按 Object 解 envelope 再把 body 转换为构造期类型。
     */
    @SuppressWarnings("unchecked")
    private MessageEnvelope<T> parse(String value) {
        MessageEnvelope<Object> raw = JSON.parseObject(value, ENVELOPE_TYPE);
        if (raw.getBody() != null) {
            raw.setBody(JSON.to(payloadType, raw.getBody()));
        }
        return (MessageEnvelope<T>) raw;
    }

    private static final Type ENVELOPE_TYPE = new TypeReference<MessageEnvelope<Object>>() {
    }.getType();
}
