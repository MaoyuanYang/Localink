# 迭代任务卡：M4.3+M4.4+M4.5 分片设计 + ShardingSphere 接入 + 订单路由表

| 字段 | 内容 |
|---|---|
| 迭代编号 | M4.3 / M4.4 / M4.5（B12 批次合并实施，设计见 [sharding.md](../sharding.md)） |
| 分支 | `feature/m4-4-5-sharding`（堆叠于 m4-1） |
| 状态 | 已完成（2026-09-22） |

---

## 1. 目标

订单域分片落地：双库（ds_0=localink 复用、ds_1=localink_1 新建）、库按 user_id%2 表按 voucher_id%2、
ShardingSphere-JDBC 逻辑数据源接管、订单 ID 切自研雪花、路由表反查。其余 11 表 SINGLE 规则落 ds_0——
**业务读路径零迁移，全量 125 例既有测试在分片数据源下原样通过**。

## 2. 设计取舍

- **ds_0 直接复用现有 localink 库**：11 张非分片表一寸不动（SINGLE 规则），只拆订单域两表为 _0/_1×2 库——
  迁移面最小化，测试破坏面最小化
- **编程式构建 SS 配置（不碰 YAML）**：snakeyaml 兼容问题的最短路径是不引入它；规则是设计决策固化在代码
- **版本 5.3.2 → 5.5.1**：5.3.2 内部 snakeyaml 1.x 与 Boot 3.5 冲突直接炸启动；5.5.1 兼容（期间 5.5.0 的
  `shardingsphere-test-util` 未发布 central 需 exclusion）
- **SINGLE 表显式清单（ds_0.表名 全限定）**：自动扫描在此环境不稳定，显式即部署契约
- **路由表非分片（ds_0）**：反查入口只有 orderId 无 user_id，路由表若分片则无法反查（死锁）；
  与订单跨物理连接有写缝隙——路由是优化不是正确性依赖，缺失时广播兜底
- **JSON-only Web 配置**：SS 传递的 jackson-dataformat-xml（运行时必需不可排除）会把 MVC 内容协商带偏到
  XML——extendMessageConverters 裁掉 XML converter；standalone MockMvc 测试显式 accept(JSON)

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `docs/sharding.md` | M4.3 分片设计（键取舍/路由表/ID 切换/踩坑实录） |
| `sql/sharding.sql` | 双库可重放脚本（4 分片表 + 路由表） |
| `localink-sharding` 模块首批代码 | ShardingDataSourceBuilder（算法注册式规则 + SINGLE 清单）+ ShardingAutoConfiguration（@AutoConfigureBefore 抢占主源）+ Properties |
| `entity/OrderRoute` + mapper | 路由表实体 |
| `VoucherOrderService(Impl)` | 订单/trace ID 切雪花；建单事务同写路由；+locateOrder 反查 |
| `config/JsonOnlyWebConfig` | 裁掉 XML converter |
| application.yml | +localink.sharding.datasources.ds_1 |
| 测试 ×3 新 + ×3 改 | 数据源直连冒烟 / 分片路由（奇偶用户落双库物理断言）/ 非分片走默认源；改：Datasource 表数（M4 形态 15/4）、两 standalone 测试加 accept(JSON) |

## 4. 验证记录（2026-09-22 本机实测）

全 reactor **217/217**（server 125 = 122 基线 + 3 新），BUILD SUCCESS。

**关键断言**：奇数 userId 订单物理落 localink_1、偶数落 localink（DriverManager 直查物理表验证，不经被测逻辑源）；
locateOrder 返回的 ds/物理表与实际一致；非分片表经逻辑源读写正常；建单链路（雪花 ID + 路由同写 + 流水分片落库）全绿。

**排障实录（连环六坑，按踩中顺序）**：
1. snakeyaml 1.x/2.x 冲突（5.3.2 炸启动）→ 升 5.5.0
2. 5.5.0 test-util 未发布 → exclusion
3. `createDataSource(Map,List,Props)` 编译过但运行报 'Key generate algorithm unregistered' → 5.5 策略须注册算法按名引用（INLINE 算法）
4. 单表 TableNotFound 连环（lk_user/lk_shop/lk_seckill_voucher 轮流炸）→ SINGLE tables 须 `ds_0.表名` 全限定（InvalidDataNodeFormat 报错是唯一明确线索）
5. 排掉 jackson-dataformat-xml 后 SS 初始化 NoClassDefFoundError:XmlMapper（它是 SS 必需）→ 恢复依赖 + MVC 层裁 converter
6. standalone MockMvc 响应变 XML → 测试显式 accept(JSON)

## 5. 学习清单

**核心知识点**
1. **分片键选"查询最多的维度"**：库键 user_id（我的订单）、表键 voucher_id（按券统计）——分片是给查询路径让路的
2. **路由表的存在理由与位置**：非分片键查询的索引层；必须可被入口键直查（放 ds_0），分片了就死锁
3. **跨库事务的现实**：ShardingSphere LOCAL 模式不跨库，同事务双写有缝隙——"优化数据"与"正确性数据"分离，后者可重建
4. **依赖树治理**：巨型依赖（SS/calcite）的传递副作用（XML converter 抢内容协商）——升级/排除前先确认是否运行时必需
5. **迁移面最小化**：SINGLE 规则让非分片表零改动——分库分表项目的成本大头从来不是规则，是迁移

**面试必问题**
1. "为什么按 user_id 分库？"——最高频查询维度、用户态事务单库闭环；追问"orderId 为什么不行"→路由表口径
2. "非分片键查询怎么办？"——路由表反查定位 + 广播兜底；追问"路由表和订单不同库怎么保证一致"→LOCAL 事务不跨库、路由是优化非正确性、缺失广播兜底
3. "ShardingSphere 怎么接的？"——编程式构建（snakeyaml 兼容）、5.5 算法注册、SINGLE 全限定清单——能讲六个坑说明真踩过
4. "分库后自增 ID？"——早统一雪花（M1 伏笔 M4 兑现）；时钟回拨处理见 m4-1-2 卡

## 6. 下一步

**M5.1/M5.2 对账任务**：Redis 流水 vs DB 订单定时比对（M3.12 的数据地基开始回报）+ PENDING 扫描 + 差异补偿与告警。
