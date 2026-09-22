package com.localink.config;

import com.localink.mq.SeckillOrderRecoverer;
import com.localink.service.VoucherOrderService;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 秒杀消费端错误处理器：指数退避（200ms ×2 封顶 1s，共 4 次尝试）→ 耗尽走 Recoverer（回滚资格）。
 * DefaultErrorHandler 是容器级处理器，经专属 containerFactory 挂到 seckill-order 监听器
 * （注解的 errorHandler 属性是另一类东西——KafkaListenerErrorHandler，POJO 异常转换，不可混用）；
 * configurer 复用 spring.kafka.* 的 yml 配置（手动 ack 等），只追加错误处理器。
 */
@Configuration
@EnableConfigurationProperties(SeckillConsumeProperties.class)
public class SeckillErrorHandlerConfig {

    @Bean
    public SeckillOrderRecoverer seckillOrderRecoverer(VoucherOrderService voucherOrderService) {
        return new SeckillOrderRecoverer(voucherOrderService);
    }

    @Bean
    public DefaultErrorHandler seckillErrorHandler(SeckillOrderRecoverer recoverer,
                                                   SeckillConsumeProperties properties) {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(properties.getBackoffInitialMs());
        backOff.setMultiplier(properties.getBackoffMultiplier());
        backOff.setMaxInterval(properties.getBackoffMaxMs());
        backOff.setMaxAttempts(properties.getMaxAttempts());
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean("seckillContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> seckillContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler seckillErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(seckillErrorHandler);
        return factory;
    }
}
