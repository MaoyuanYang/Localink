package com.localink.idempotent.config;

import com.localink.idempotent.LocalLockCache;
import com.localink.idempotent.RepeatExecuteLimitAspect;
import com.localink.lock.DistributedLock;
import com.localink.lock.config.LockAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 排在 LockAutoConfiguration 之后（com.localink.idempotent 字母序先于 com.localink.lock，
 * 不显式 after 则 @ConditionalOnBean(DistributedLock) 静默跳过——B5 教训）。
 */
@AutoConfiguration(after = LockAutoConfiguration.class)
@ConditionalOnClass(DistributedLock.class)
@EnableConfigurationProperties(IdempotentProperties.class)
public class IdempotentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LocalLockCache.class)
    public LocalLockCache localLockCache() {
        return new LocalLockCache();
    }

    @Bean
    @ConditionalOnMissingBean(RepeatExecuteLimitAspect.class)
    @ConditionalOnBean({DistributedLock.class, StringRedisTemplate.class})
    public RepeatExecuteLimitAspect repeatExecuteLimitAspect(
            StringRedisTemplate redisTemplate, DistributedLock distributedLock,
            LocalLockCache localLockCache, IdempotentProperties properties) {
        return new RepeatExecuteLimitAspect(redisTemplate, distributedLock, localLockCache, properties);
    }
}
