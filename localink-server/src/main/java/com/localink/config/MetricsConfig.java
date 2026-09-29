package com.localink.config;

import com.localink.common.metrics.MetricsPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标端口实现（PRD §5 可观测承诺）：回滚失败/限流拒绝/对账补偿等业务计数器
 * 经 Micrometer 暴露到 /actuator/metrics。actuator 不在类路径时本配置整体失效，
 * starter 侧 MetricsPort 回落 NOOP。
 */
@Configuration
@ConditionalOnClass(MeterRegistry.class)
public class MetricsConfig {

    @Bean
    public MetricsPort metricsPort(MeterRegistry meterRegistry) {
        return (name, tags) -> {
            if (tags == null || tags.length == 0) {
                meterRegistry.counter(name).increment();
                return;
            }
            if (tags.length % 2 != 0) {
                throw new IllegalArgumentException("metrics tags 必须成对出现: " + name);
            }
            var builder = Tags.empty();
            for (int i = 0; i < tags.length; i += 2) {
                builder = builder.and(Tag.of(tags[i], tags[i + 1]));
            }
            meterRegistry.counter(name, builder).increment();
        };
    }
}
