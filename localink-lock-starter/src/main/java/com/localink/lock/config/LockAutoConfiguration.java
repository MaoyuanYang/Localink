package com.localink.lock.config;

import com.localink.lock.DistributedLock;
import com.localink.lock.aspect.ServiceLockAspect;
import com.localink.lock.impl.RedissonDistributedLock;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(RedissonClient.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(LockProperties.class)
public class LockAutoConfiguration {

    /**
     * 与 cache-starter 按 @ConditionalOnMissingBean 互让：两者从 spring.data.redis.* 同参构建，
     * 先装配者生效、后者退让，模块各自独立可用。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    @ConditionalOnBean(RedisProperties.class)
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        SingleServerConfig singleServer = config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            singleServer.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    @ConditionalOnBean(RedissonClient.class)
    public DistributedLock distributedLock(RedissonClient redissonClient) {
        return new RedissonDistributedLock(redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean(ServiceLockAspect.class)
    @ConditionalOnBean(DistributedLock.class)
    public ServiceLockAspect serviceLockAspect(DistributedLock distributedLock, LockProperties lockProperties) {
        return new ServiceLockAspect(distributedLock, lockProperties);
    }
}
