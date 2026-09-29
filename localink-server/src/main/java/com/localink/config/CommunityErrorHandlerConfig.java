package com.localink.config;

import com.localink.service.PostAuditService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 社区消费端错误处理器（B-7）：post-audit / post-search 原走 Boot 默认
 * FixedBackOff(0,9)，10 次后静默丢弃——审核消息丢失=风险帖永久可见且无告警。
 * 对齐秒杀链路的"指数退避 + 耗尽 Recoverer"口径：
 * <ul>
 *   <li>post-audit 耗尽 → 强制驳回（fail-closed：宁可错杀不可漏审；DB 同故障时仅 error 告警）</li>
 *   <li>post-search 耗尽 → error 告警留档（ES 是派生视图，rebuild 可重算；不阻塞消费位）</li>
 * </ul>
 * 退避参数复用 localink.seckill.consume.*（同口径统一治理）。
 */
@Slf4j
@Configuration
public class CommunityErrorHandlerConfig {

    @Bean
    public DefaultErrorHandler postAuditErrorHandler(PostAuditService postAuditService,
                                                     SeckillConsumeProperties properties) {
        return new DefaultErrorHandler((record, exception) -> {
            Long postId = null;
            try {
                // record.value() 是 MessageEnvelope 包装（与 AbstractKafkaConsumer.dispatch 同构解析）
                com.alibaba.fastjson2.JSONObject envelope = com.alibaba.fastjson2.JSON.parseObject(
                        String.valueOf(record.value()));
                if (envelope != null && envelope.getJSONObject("body") != null) {
                    postId = envelope.getJSONObject("body").getLong("postId");
                }
            } catch (Exception ignore) {
                // 信封解析失败：原样告警，无法定位帖子
            }
            log.error("异步复审消息重试耗尽[告警]: 强制驳回兜底, postId={}, cause=",
                    postId == null ? "unknown" : postId, exception);
            if (postId != null) {
                try {
                    postAuditService.forceReject(postId);
                } catch (Exception e) {
                    log.error("强制驳回亦失败(存储同故障?), 人工介入, postId={}", postId, e);
                }
            }
        }, backOff(properties));
    }

    @Bean
    public DefaultErrorHandler postSearchErrorHandler(SeckillConsumeProperties properties) {
        return new DefaultErrorHandler((record, exception) ->
                log.error("ES 同步消息重试耗尽[告警]: 派生视图漂移, 可 rebuildAll 重算, record={}",
                        safeSummary(record), exception), backOff(properties));
    }

    @Bean("postAuditContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> postAuditContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler postAuditErrorHandler) {
        return buildFactory(configurer, consumerFactory, postAuditErrorHandler);
    }

    @Bean("postSearchContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> postSearchContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler postSearchErrorHandler) {
        return buildFactory(configurer, consumerFactory, postSearchErrorHandler);
    }

    private ConcurrentKafkaListenerContainerFactory<Object, Object> buildFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    private ExponentialBackOff backOff(SeckillConsumeProperties properties) {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(properties.getBackoffInitialMs());
        backOff.setMultiplier(properties.getBackoffMultiplier());
        backOff.setMaxInterval(properties.getBackoffMaxMs());
        backOff.setMaxAttempts(properties.getMaxAttempts());
        return backOff;
    }

    private String safeSummary(ConsumerRecord<?, ?> record) {
        return record == null ? "null" : "topic=" + record.topic() + ",key=" + record.key();
    }
}
