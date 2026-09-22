package com.localink.ratelimit.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M3.13 起步配置：key 前缀对齐全局 `lk:` 约定。场景化配置（IP/用户维度、白名单、封禁）
 * 属 M3.14 RateLimitHandler 职责，届时扩展本类。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.ratelimit")
public class RateLimitProperties {

    /**
     * 限流 key 统一前缀（默认 lk:，与 KeyManage 环境前缀约定一致）。
     */
    private String keyPrefix = "lk:";

    /**
     * 限流器总开关（M3.14 Handler 的熔断口径：故障时放行保业务）。
     */
    private boolean enabled = true;
}
