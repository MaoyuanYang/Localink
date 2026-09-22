package com.localink.sharding;

import com.localink.sharding.config.ShardingProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.apache.shardingsphere.single.config.SingleRuleConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * ShardingSphere 逻辑数据源工厂（M4.4，编程式构建——项目对 snakeyaml 的兼容处理就是不碰 YAML）：
 * ds_0 复用 spring.datasource 同参，ds_1..n 来自 localink.sharding.datasources。
 * 规则固化：订单域两表（库 user_id % 2、表 voucher_id % 2），其余表 SINGLE 规则落 ds_0。
 */
public final class ShardingDataSourceBuilder {

    public static final String DEFAULT_DS = "ds_0";

    private ShardingDataSourceBuilder() {
    }

    public static DataSource build(DataSourceProperties primary, ShardingProperties properties)
            throws SQLException {
        Map<String, DataSource> dataSourceMap = new LinkedHashMap<>();
        dataSourceMap.put(DEFAULT_DS, hikari(primary.getUrl(), primary.getUsername(),
                primary.getPassword(), 10, 3000));
        properties.getDatasources().forEach((name, config) -> dataSourceMap.put(name,
                hikari(config.getUrl(), config.getUsername(), config.getPassword(),
                        config.getMaximumPoolSize(), config.getConnectionTimeout())));

        List<RuleConfiguration> rules = List.of(shardingRule(), singleRule());

        Properties props = new Properties();
        props.setProperty("sql-show", String.valueOf(properties.isSqlShow()));
        return ShardingSphereDataSourceFactory.createDataSource("localink_logic",
                new org.apache.shardingsphere.infra.config.mode.ModeConfiguration("Standalone", null),
                dataSourceMap, rules, props);
    }

    /**
     * 订单域分片规则：lk_voucher_order 与 lk_voucher_reconcile_log 同构（库 user_id、表 voucher_id，各取模 2）。
     * 5.5 起策略按算法名引用注册表（inline 表达式作为 INLINE 算法注册，表算法每表一个——表达式是字面替换）。
     */
    private static RuleConfiguration shardingRule() {
        ShardingRuleConfiguration rule = new ShardingRuleConfiguration();
        rule.getShardingAlgorithms().put("db-mod-user", inlineAlgorithm("ds_${user_id % 2}"));
        rule.getShardingAlgorithms().put("table-mod-voucher-order",
                inlineAlgorithm("lk_voucher_order_${voucher_id % 2}"));
        rule.getShardingAlgorithms().put("table-mod-voucher-log",
                inlineAlgorithm("lk_voucher_reconcile_log_${voucher_id % 2}"));
        rule.getTables().add(tableRule("lk_voucher_order", "table-mod-voucher-order"));
        rule.getTables().add(tableRule("lk_voucher_reconcile_log", "table-mod-voucher-log"));
        return rule;
    }

    private static AlgorithmConfiguration inlineAlgorithm(String expression) {
        Properties props = new Properties();
        props.setProperty("algorithm-expression", expression);
        return new AlgorithmConfiguration("INLINE", props);
    }

    private static ShardingTableRuleConfiguration tableRule(String logicTable, String tableAlgorithmName) {
        ShardingTableRuleConfiguration table =
                new ShardingTableRuleConfiguration(logicTable, "ds_${0..1}." + logicTable + "_${0..1}");
        table.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration("user_id",
                "db-mod-user"));
        table.setTableShardingStrategy(new StandardShardingStrategyConfiguration("voucher_id",
                tableAlgorithmName));
        return table;
    }

    /**
     * 非分片表（用户/券/商户等 11 张）显式声明落 ds_0——5.5 要求全限定格式（ds_0.表名），
     * 显式清单即部署契约（新增非分片表须同步此处，任务卡有记录）。
     */
    private static RuleConfiguration singleRule() {
        SingleRuleConfiguration rule = new SingleRuleConfiguration();
        rule.setTables(java.util.Arrays.asList(
                DEFAULT_DS + ".lk_user", DEFAULT_DS + ".lk_shop_type", DEFAULT_DS + ".lk_shop",
                DEFAULT_DS + ".lk_voucher", DEFAULT_DS + ".lk_seckill_voucher",
                DEFAULT_DS + ".lk_rollback_failure_log", DEFAULT_DS + ".lk_order_route",
                DEFAULT_DS + ".lk_follow", DEFAULT_DS + ".lk_post",
                DEFAULT_DS + ".lk_post_comment", DEFAULT_DS + ".lk_post_like"));
        rule.setDefaultDataSource(DEFAULT_DS);
        return rule;
    }

    private static DataSource hikari(String url, String username, String password,
                                     int poolSize, long timeout) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setConnectionTimeout(timeout);
        config.setPoolName("sharding-ds");
        return new HikariDataSource(config);
    }
}
