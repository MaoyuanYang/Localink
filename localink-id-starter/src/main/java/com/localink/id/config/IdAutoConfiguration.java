package com.localink.id.config;

import com.localink.id.SnowflakeIdGenerator;
import com.localink.id.WorkIdAllocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * M4.1 固定机器位 + M4.2 Redis 轮转分配：未配置固定 workId 且 Redis 可用时走分配器；
 * 分配器故障兜底固定 0（启动失败优于静默不可用，日志告警人工介入）。
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(IdProperties.class)
public class IdAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "allocateWorkIdScript")
    public RedisScript<Long> allocateWorkIdScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/allocate_work_id.lua")));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public WorkIdAllocator workIdAllocator(StringRedisTemplate redisTemplate,
                                           RedisScript<Long> allocateWorkIdScript,
                                           IdProperties properties) {
        return new WorkIdAllocator(redisTemplate, allocateWorkIdScript, "lk:");
    }

    @Bean
    @ConditionalOnMissingBean(SnowflakeIdGenerator.class)
    public SnowflakeIdGenerator snowflakeIdGenerator(IdProperties properties,
                                                     org.springframework.beans.factory.ObjectProvider<WorkIdAllocator> allocatorProvider) {
        long workId = resolveWorkId(properties, allocatorProvider);
        long dataCenterId = properties.getDataCenterId() != null ? properties.getDataCenterId() : 0L;
        log.info("雪花 ID 生成器启动: workId={}, dataCenterId={}", workId, dataCenterId);
        return new SnowflakeIdGenerator(workId, dataCenterId, properties.getMaxBackwardMs(),
                System::currentTimeMillis);
    }

    private long resolveWorkId(IdProperties properties,
                               org.springframework.beans.factory.ObjectProvider<WorkIdAllocator> allocatorProvider) {
        if (properties.getWorkId() != null) {
            return properties.getWorkId();
        }
        WorkIdAllocator allocator = allocatorProvider.getIfAvailable();
        if (allocator != null) {
            try {
                return allocator.allocate();
            } catch (Exception e) {
                log.error("Redis 轮转分配 workId 失败, 回退固定 0（人工介入检查 Redis）", e);
            }
        }
        return 0L;
    }
}
