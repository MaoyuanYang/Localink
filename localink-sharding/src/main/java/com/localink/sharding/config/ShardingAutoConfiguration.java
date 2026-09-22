package com.localink.sharding.config;

import com.localink.sharding.ShardingDataSourceBuilder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 主数据源切换为 ShardingSphere 逻辑源（M4.4）。@AutoConfigureBefore(DataSourceAutoConfiguration)：
 * 先于 Boot 默认 Hikari 评估，@ConditionalOnMissingBean 即可独占数据源位——
 * MyBatis-Plus 与事务管理器全部无感走逻辑源。localink.sharding.enabled=false 时退回单库直连。
 */
@AutoConfiguration
@AutoConfigureBefore(DataSourceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "localink.sharding", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ShardingProperties.class)
public class ShardingAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource shardingDataSource(DataSourceProperties dataSourceProperties,
                                         ShardingProperties properties) throws SQLException {
        return ShardingDataSourceBuilder.build(dataSourceProperties, properties);
    }
}
