package com.localink.delay.config;

import com.localink.delay.DelayQueueConsumer;
import com.localink.delay.DelayQueuePublisher;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

/**
 * 延迟队列自动装配（M5-B）。RedissonClient 与 lock/cache-starter 按 @ConditionalOnMissingBean
 * 互让（同参构建、先装配者生效）；全部 DelayQueueConsumer bean 由 SmartLifecycle 统一启停。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(RedissonClient.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(DelayProperties.class)
public class DelayAutoConfiguration {

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
    @ConditionalOnMissingBean
    @ConditionalOnBean(RedissonClient.class)
    public DelayQueuePublisher delayQueuePublisher(RedissonClient redissonClient,
                                                   DelayProperties properties) {
        return new DelayQueuePublisher(redissonClient, properties);
    }

    /**
     * 消费者生命周期：容器就绪后统一 start（每分片一个消费线程），关闭时停。
     */
    @Bean
    public DelayConsumerLifecycle delayConsumerLifecycle(ListableBeanFactory beanFactory) {
        return new DelayConsumerLifecycle(beanFactory);
    }

    static class DelayConsumerLifecycle implements SmartLifecycle {

        private final ListableBeanFactory beanFactory;
        private boolean running;

        DelayConsumerLifecycle(ListableBeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void start() {
            beanFactory.getBeansOfType(DelayQueueConsumer.class)
                    .forEach((name, consumer) -> consumer.start());
            running = true;
        }

        @Override
        public void stop() {
            beanFactory.getBeansOfType(DelayQueueConsumer.class)
                    .forEach((name, consumer) -> consumer.stop());
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
