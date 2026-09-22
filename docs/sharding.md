# 分片设计（M4.3）

> M4.4/M4.5 的实施依据。双库由 `sql/sharding.sql` 建立；规则固化于 `localink-sharding` 模块 `ShardingDataSourceBuilder`。

## 1. 分片目标与范围

仅订单域分片：写热点集中在秒杀建单（lk_voucher_order + 随单的 lk_voucher_reconcile_log），
其余 11 表读多写少、量级远离瓶颈——**不为了分片而分片**，迁移面与运维面最小化。

## 2. 分片键取舍

| 层 | 键 | 规则 | 论证 |
|---|---|---|---|
| 库 | user_id | `ds_${user_id % 2}` | 最高频查询"我的订单"带 user_id——单库命中免广播；同用户订单同库，用户态事务（关单/退款）单库内闭环 |
| 表 | voucher_id | `lk_voucher_order_${voucher_id % 2}` | 运营侧"按券统计"带 voucher_id；与库键正交，数据四分打散 |

- **为什么不用 orderId 做分片键**：雪花 ID 均匀但业务查询极少带 orderId 进库（带也是反查路由表）；
  user_id 是天然租户边界
- **取模而非哈希**：2 库取模即均匀且可人工换算（redis-cli 排障时 `uid % 2` 心算定位）；扩容再换一致性哈希
- **放弃的时间线**：库表数各 2（4 分片）是教学取舍——真实量级下单表足够，先解决"双库与路由体系怎么建"，
  分片数扩展只改表达式

## 3. 路由表（M4.5）

`lk_order_route(order_id, user_id, voucher_id)`，非分片落 ds_0：

- **写**：建单事务同 insert（orderId → 分片键映射）
- **读**：`locateOrder(orderId)`：路由表取键 → 计算 ds/物理表 → 带键精确查询，免全分片广播
- **一致性口径**：订单（分片库）与路由（ds_0）跨物理连接，ShardingSphere LOCAL 事务不跨库——
  写入有缝隙（订单成功路由缺失）。**路由是优化不是正确性依赖**：缺失时广播兜底（M4.5 实现为 locateOrder 降级）
- **为什么路由表不分片**：反查入口只有 orderId，无 user_id 可路由——分片了就查不了，死锁

## 4. 全局 ID 切换

订单/流水/trace 的 ID 自 M4.4 起由 id-starter 雪花生成（M4.1 决策兑现）；自增 ID 在分片下必然冲突，
MyBatis-Plus ASSIGN_ID 退役（其余表沿用，无业务影响）。

## 5. ShardingSphere 接入要点（踩坑实录，详见任务卡）

1. **版本 5.3.2 → 5.5.1**：5.3.2 内部依赖 snakeyaml 1.x 与 Boot 3.5 的 2.x 冲突（`Representer <init>` 失败）；
   5.5.x 官方兼容。且 5.4 起 artifactId 由 `shardingsphere-jdbc-core` 改为 `shardingsphere-jdbc`
2. **编程式构建（不碰 YAML）**：项目对 snakeyaml 的"兼容处理"就是完全绕开 YAML 配置，
   `ShardingDataSourceBuilder` 纯 Java API 构建规则
3. **5.5 策略必须注册算法再按名引用**：`StandardShardingStrategyConfiguration(column, algorithmName)`；
   inline 表达式作为 `INLINE` 算法注册进 `shardingAlgorithms`（表算法每表一个——表达式是字面替换）
4. **SINGLE 规则的 tables 要全限定**（`ds_0.lk_user`）：纯表名报 InvalidDataNodeFormat；
   显式 11 表清单即部署契约（新增非分片表须同步）
5. **jackson-dataformat-xml 传递依赖**：SS 运行时必需（内部 XmlMapper），不可排除；副作用是 MVC 把
   Result 协商成 XML——`JsonOnlyWebConfig.extendMessageConverters` 裁掉 XML converter 收回决定权
6. **@AutoConfigureBefore(DataSourceAutoConfiguration)**：starter 抢占主数据源位，Boot 默认 Hikari 退让；
   MyBatis-Plus 与事务管理器无感切换
