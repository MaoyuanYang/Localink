package com.localink.cache.config;

import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.cache.impl.DefaultRedisCache;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * cache-starter 自动装配：KeyBuilder（前缀可配）+ 宿主存在 StringRedisTemplate 时注册 RedisCache 门面。
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
}
