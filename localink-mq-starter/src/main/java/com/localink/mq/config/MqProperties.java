package com.localink.mq.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "localink.mq")
public class MqProperties {

    /**
     * 显式注册的 topic（KafkaAdmin 启动建表）：localink.mq.topics.<topic名>.partitions / replicas。
     * 不配置则依赖 broker auto-create（默认分区数以 broker 配置为准）。
     */
    private Map<String, TopicSpec> topics = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class TopicSpec {

        /**
         * 分区数（默认 3，与 broker KAFKA_NUM_PARTITIONS 对齐）。
         */
        private int partitions = 3;

        /**
         * 副本数（单节点开发环境为 1）。
         */
        private int replicas = 1;
    }
}
