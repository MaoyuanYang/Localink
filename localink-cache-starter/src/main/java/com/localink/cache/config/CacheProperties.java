package com.localink.cache.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
}
