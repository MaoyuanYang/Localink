package com.localink.lock.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "localink.lock")
public class LockProperties {

    /**
     * 锁 key 统一前缀（环境隔离，取值与 cache-starter 的 keyPrefix 同族约定）。
     */
    private String keyPrefix = "lk:lock:";
}
