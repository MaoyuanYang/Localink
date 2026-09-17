package com.localink.mq;

import com.localink.cache.LocalCache;
import com.localink.cache.LocalCacheRegistry;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 本地缓存失效广播消费者：每实例独立消费组（random.uuid）实现组间全量广播、组内单播；
 * auto.offset.reset 覆盖为 latest，新实例不重放历史失效消息。
 * 未知缓存别名属"永久不可处理"，警告后跳过（ack）而非无限重投；踢缓存幂等，重复消息无害。
 */
@Slf4j
@Component
public class CacheInvalidationConsumer extends AbstractKafkaConsumer<CacheInvalidationMessage> {

    private final LocalCacheRegistry localCacheRegistry;

    public CacheInvalidationConsumer(LocalCacheRegistry localCacheRegistry) {
        super(CacheInvalidationMessage.class);
        this.localCacheRegistry = localCacheRegistry;
    }

    @KafkaListener(
            topics = MqTopics.CACHE_INVALIDATION,
            groupId = "localink-cache-invalidation-${random.uuid}",
            properties = {"auto.offset.reset:latest"})
    void onMessage(String value, Acknowledgment ack) {
        dispatch(value, ack);
    }

    @Override
    protected void doConsume(CacheInvalidationMessage payload, MessageEnvelope<CacheInvalidationMessage> envelope) {
        LocalCache<Object, Object> cache;
        try {
            cache = localCacheRegistry.cache(payload.cache());
        } catch (LocalinkException e) {
            if (BaseCode.SYSTEM_ERROR.getCode() == e.getCode()) {
                log.warn("失效广播指向未注册的本地缓存, 跳过: cache={}", payload.cache());
                return;
            }
            throw e;
        }
        cache.invalidate(payload.key());
    }
}
