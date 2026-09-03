package com.localink.cache.config;

import com.localink.cache.BloomFilterRegistry;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.cache.impl.DefaultRedisCache;
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
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * cache-starter 自动装配：KeyBuilder（前缀可配）+ 宿主存在 StringRedisTemplate 时注册 RedisCache 门面，
 * 类路径存在 Redisson 时注册 RedissonClient（单机模式，复用 spring.data.redis 连接参数）与布隆过滤器注册表。
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeyBuilder keyBuilder(CacheProperties cacheProperties) {
        return new KeyBuilder(cacheProperties.getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(RedisCache.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    public RedisCache redisCache(StringRedisTemplate stringRedisTemplate) {
        return new DefaultRedisCache(stringRedisTemplate);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnClass(RedissonClient.class)
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
    @ConditionalOnMissingBean(BloomFilterRegistry.class)
    @ConditionalOnBean(RedissonClient.class)
    public BloomFilterRegistry bloomFilterRegistry(RedissonClient redissonClient,
                                                   KeyBuilder keyBuilder,
                                                   CacheProperties cacheProperties) {
        return new BloomFilterRegistry(redissonClient, keyBuilder, cacheProperties);
    }
}
