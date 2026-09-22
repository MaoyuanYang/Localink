package com.localink.datasource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DatasourceSmokeTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shopTypeSeedDataLoaded() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lk_shop_type", Integer.class);
        assertNotNull(count);
        assertEquals(10, count);
    }

    @Test
    void shopSeedDataLoaded() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lk_shop", Integer.class);
        assertNotNull(count);
        assertTrue(count >= 10);
    }

    @Test
    void allTablesExistInM4ShardedLayout() throws Exception {
        // M4 分片后：ds_0（localink）= 11 单表 + 订单域 2×2 分片表 + 路由表 = 15；ds_1（localink_1）= 订单域 4 张
        assertEquals(15, physicalTableCount("localink"), "ds_0：11 单表 + 4 分片表 + 1 路由表");
        assertEquals(4, physicalTableCount("localink_1"), "ds_1：仅订单域分片表");
    }

    private int physicalTableCount(String schema) throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:mysql://localhost:3306/" + schema + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                "root", "localink123");
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + schema
                             + "' AND table_name LIKE 'lk\\_%'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
