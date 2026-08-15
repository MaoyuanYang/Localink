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
    void allTwelveTablesExist() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'localink' AND table_name LIKE 'lk\\_%'",
                Integer.class);
        assertNotNull(count);
        assertEquals(12, count);
    }
}
