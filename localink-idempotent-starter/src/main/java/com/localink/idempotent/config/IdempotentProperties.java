package com.localink.idempotent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "localink.idempotent")
public class IdempotentProperties {

    /**
     * 幂等 key 统一前缀（环境隔离）：marker:{name}:{key} 与 lock:{name}:{key} 共用。
     */
    private String keyPrefix = "lk:idem:";
}
