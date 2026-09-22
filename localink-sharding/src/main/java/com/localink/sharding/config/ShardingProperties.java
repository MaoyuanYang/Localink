package com.localink.sharding.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分片配置（M4.4）：ds_0 固定复用 spring.datasource.* 同参（LockAutoConfiguration 同款先例），
 * 其余数据源在此声明；分片规则固化在代码（本项目订单域规则是设计决策不是运维参数）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.sharding")
public class ShardingProperties {

    /**
     * 是否接管主数据源为 ShardingSphere 逻辑源（false 时退回单库直连）。
     */
    private boolean enabled = true;

    /**
     * 额外数据源（ds_1..n）：名 → 连接参数。ds_0 由 spring.datasource 构建，不在此声明。
     */
    private Map<String, DataSourceConfig> datasources = new LinkedHashMap<>();

    /**
     * 打印分片路由 SQL（排障开关）。
     */
    private boolean sqlShow = false;

    @Getter
    @Setter
    public static class DataSourceConfig {

        private String url;

        private String username;

        private String password;

        private int maximumPoolSize = 10;

        private long connectionTimeout = 3000;
    }
}
