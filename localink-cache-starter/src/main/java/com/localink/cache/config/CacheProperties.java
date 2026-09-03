package com.localink.cache.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * cache-starter 配置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.cache")
public class CacheProperties {

    /**
     * key 统一前缀（环境可配，如 dev 环境改为 lk:dev:）。
     */
    private String keyPrefix = "lk:";

    /**
     * 布隆过滤器配置。
     */
    private Bloom bloom = new Bloom();

    @Getter
    @Setter
    public static class Bloom {

        /**
         * 过滤器注册表：key 为业务别名（如 shop），value 为该过滤器的参数。
         */
        private Map<String, Filter> filters = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Filter {

        /**
         * key 模板（走 Key 治理统一加前缀，如 shop:bloom:id），无 %s 占位。
         */
        private String keyTemplate;

        /**
         * 预期插入量，决定位数组大小。
         */
        private long expectedInsertions = 10000;

        /**
         * 误判率上限。
         */
        private double falseProbability = 0.01;
    }
}
