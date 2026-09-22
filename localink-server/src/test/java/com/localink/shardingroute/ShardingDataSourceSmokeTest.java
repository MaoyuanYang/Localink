package com.localink.shardingroute;

import com.localink.sharding.ShardingDataSourceBuilder;
import com.localink.sharding.config.ShardingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不启 Spring 的数据源直连冒烟：隔离 ShardingSphere 与应用上下文，定位单表元数据加载问题。
 */
class ShardingDataSourceSmokeTest {

    @Test
    void singleAndShardedTablesQueryable() throws Exception {
        DataSourceProperties primary = new DataSourceProperties();
        primary.setUrl("jdbc:mysql://localhost:3306/localink?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        primary.setUsername("root");
        primary.setPassword("localink123");

        ShardingProperties.DataSourceConfig ds1 = new ShardingProperties.DataSourceConfig();
        ds1.setUrl("jdbc:mysql://localhost:3306/localink_1?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        ds1.setUsername("root");
        ds1.setPassword("localink123");

        ShardingProperties properties = new ShardingProperties();
        properties.setSqlShow(true);
        properties.getDatasources().put("ds_1", ds1);

        DataSource dataSource = ShardingDataSourceBuilder.build(primary, properties);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertTrue(queryCount(statement, "SELECT COUNT(*) FROM lk_user") >= 0, "单表 lk_user 可查");
            assertTrue(queryCount(statement, "SELECT COUNT(*) FROM lk_shop") >= 0, "单表 lk_shop 可查");
            assertTrue(queryCount(statement, "SELECT COUNT(*) FROM lk_voucher") >= 0, "单表 lk_voucher 可查");
            assertTrue(queryCount(statement, "SELECT COUNT(*) FROM lk_seckill_voucher") >= 0, "单表 lk_seckill_voucher 可查");
            assertTrue(queryCount(statement, "SELECT COUNT(*) FROM lk_voucher_order") >= 0, "分片表 lk_voucher_order 可查（广播归并）");
            assertTrue(queryCount(statement, "SELECT COUNT(*) FROM lk_order_route") >= 0, "单表 lk_order_route 可查");
        }
    }

    private int queryCount(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
