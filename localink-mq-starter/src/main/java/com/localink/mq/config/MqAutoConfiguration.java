package com.localink.mq.config;

import com.localink.mq.MessageProducer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

/**
 * 排在 KafkaAutoConfiguration 之后：@ConditionalOnBean(KafkaTemplate) 依赖其先注册
 * （自动配置默认按类名字母序，com.localink 先于 org.springframework，需显式 after）。
 */
@Slf4j
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(MqProperties.class)
public class MqAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MessageProducer.class)
    @ConditionalOnBean(KafkaTemplate.class)
    public MessageProducer messageProducer(KafkaTemplate<String, String> kafkaTemplate) {
        return new MessageProducer(kafkaTemplate);
    }

    /**
     * 按 localink.mq.topics 显式建 topic（分区数/副本数可见可控）。List&lt;NewTopic&gt; 不是
     * NewTopic 类型 bean、KafkaAdmin 扫描不到，故此处主动调用 createOrModifyTopics；
     * broker 不可达时只告警不阻断启动（与 KafkaAdmin fail-fast=false 语义对齐）。
     */
    @Bean
    public List<NewTopic> mqTopics(MqProperties properties, KafkaAdmin kafkaAdmin) {
        List<NewTopic> topics = properties.getTopics().entrySet().stream()
                .map(entry -> new NewTopic(entry.getKey(),
                        entry.getValue().getPartitions(),
                        (short) entry.getValue().getReplicas()))
                .toList();
        if (!topics.isEmpty()) {
            try {
                kafkaAdmin.createOrModifyTopics(topics.toArray(NewTopic[]::new));
            } catch (Exception e) {
                log.warn("MQ topic 创建失败（broker 不可达？启动继续）: {}", e.getMessage());
            }
        }
        return topics;
    }
}
