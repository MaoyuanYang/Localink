package com.localink.ratelimit.config;

import com.localink.ratelimit.RateLimitAdmin;
import com.localink.ratelimit.RateLimiter;
import com.localink.ratelimit.aspect.RateLimitAspect;
import com.localink.ratelimit.impl.RedisRateLimiter;
import com.localink.ratelimit.user.RateLimitUserResolver;
import org.springframework.beans.factory.ObjectProvider;
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

@AutoConfiguration
@ConditionalOnBean(StringRedisTemplate.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "tokenBucketScript")
    public RedisScript<String> tokenBucketScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/token_bucket.lua")));
        script.setResultType(String.class);
        return script;
    }

    @Bean
    @ConditionalOnMissingBean(name = "slidingWindowScript")
    public RedisScript<String> slidingWindowScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/sliding_window.lua")));
        script.setResultType(String.class);
        return script;
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter rateLimiter(StringRedisTemplate redisTemplate,
                                   RedisScript<String> tokenBucketScript,
                                   RedisScript<String> slidingWindowScript,
                                   RateLimitProperties properties) {
        return new RedisRateLimiter(redisTemplate, tokenBucketScript, slidingWindowScript, properties);
    }

    @Bean
    @ConditionalOnMissingBean(RateLimitAdmin.class)
    public RateLimitAdmin rateLimitAdmin(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        return new RateLimitAdmin(redisTemplate, properties.getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean(RateLimitAspect.class)
    public RateLimitAspect rateLimitAspect(RateLimiter rateLimiter,
                                           RateLimitAdmin rateLimitAdmin,
                                           RateLimitProperties properties,
                                           ObjectProvider<RateLimitUserResolver> userResolverProvider) {
        return new RateLimitAspect(rateLimiter, rateLimitAdmin, properties, userResolverProvider);
    }
}
