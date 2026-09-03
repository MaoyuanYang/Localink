# 迭代任务卡：M2.4 缓存穿透：空值缓存

| 字段 | 内容 |
|---|---|
| 迭代编号 | M2.4 |
| 分支 | `feature/m2-4-null-cache` |
| 状态 | 进行中（实现与验证已完成，PR 待审） |

---

## 1. 目标

按铁律②"先暴露问题→再解决问题"：先用不存在的 id 轰炸 `GET /api/shop/{id}`，以 MySQL `Com_select` 计数为实证，暴露 M2.3 刻意保留的缓存穿透问题（无效 id 每次穿透到库）；随后在 `detail` 的 DB 未命中分支落地上位机方案——**空值缓存**：写入空串标记 + 短 TTL 兜底，同一无效 id 的后续请求被 Redis 吸收，不再触库。

涉及表：`lk_shop`（无变更）；涉及接口：`GET /api/shop/{id}`（对外行为不变——不存在仍返回 NOT_FOUND，变化仅在内部：第二次起不查库）；涉及 Redis Key：复用 `lk:shop:info:{id}`（DB 未命中时写空串 `""`，TTL 2 分钟，**不新增 key**）。

## 2. 设计取舍

- **空值标记选空串 `""`，复用 SHOP_INFO 同 key，不新增独立 key**：cache-starter 序列化约定"value 为 String 原样写入"（M2.1 决策），`set(key, "", ttl)` 写入 0 字节空串，`getString` 返回值天然三分：`null`=key 不存在（真 miss，查库）、`""`=空值命中（直接 NOT_FOUND，不查库）、非空=VO JSON（真命中直返）。备选否决：① 独立 `shop:empty:%s` key——每次 miss 多查一个 key、KeyManage 多一项，防护语义完全相同，纯开销无收益；② 哨兵 JSON `"{}"`——`get(key, ShopVO.class)` 会解析成全 null 字段对象，与真实 VO 无法稳定区分，靠字段判空太脆弱
- **空值 TTL 2 分钟（`SHOP_NULL_CACHE_TTL`），与正缓存 30 分钟解耦**：空值是"猜测性缓存"——写入瞬间就可能是错的（数据导入/修复把商户补上了）。短 TTL 让误判窗口小，最长 2 分钟自愈；太短（秒级）则 TTL 过期后攻击请求又穿透，防护形同虚设；太长（如与正缓存同 30 分钟）则新补录商户最长半小时查不到，业务不可接受。2 分钟是数据库保护与数据修正时效的折中
- **读链路从 `get(key, ShopVO.class)` 改为 `getString` raw 读 + 手动反序列化**：空值判定必须发生在反序列化之前——`get` 对空串会执行 `JSON.parseObject("", ShopVO.class)` 得到 null，与"key 不存在"的 null 混淆，无法区分真 miss 与空值命中。raw 读一次覆盖全部三分支，不多一次网络往返；反序列化复用 starter 公开的 `RedisJsonCodec.deserialize`，与 `get(key, type)` 内部实现逐行等价，行为无漂移
- **空值命中的响应与 DB 未命中完全一致（NOT_FOUND "商户不存在"）**：空值缓存是纯内部防护，不改变对外契约。代价是测试无法从响应体区分"库说的 404"与"空值说的 404"，只能从 Redis 状态（标记值 + TTL）与 DB 查询次数（spy）侧面断言
- **update/delete 不清理空值标记**：写路径 `requireExists` 抛 NOT_FOUND 时还没走到缓存操作；id 不存在的商户本就不会有正缓存。空值标记靠自身 2 分钟 TTL 过期，不为它增加写路径代码
- **create 不清理空值标记**：自增 id 永不复用，新建商户不可能命中历史空值标记；手动带显式 id 导入属运维场景，2 分钟自愈可接受
- **空值缓存的固有缺陷刻意保留——随机 id 仍可打爆 Redis 内存**：攻击者每次换随机 id 就是一次全新 miss，每个 miss 都写一个空值 key，空值缓存只防"重复 id 轰炸"，不防"随机 id 扫射"。根治手段是布隆过滤器（M2.8/M2.9），演进证据链下一环
- **穿透证据选 MySQL `Com_select` 计数而非日志推理**：`SHOW GLOBAL STATUS LIKE 'Com_select'` 前后差值即 SELECT 执行次数。同一脚本轰炸 20 次，修复前差值 ≈20（每次穿透到库），修复后 ≈1（首 miss 写标记，其余被吸收），前后对比是最硬的实证

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `service/impl/ShopServiceImpl.java` | `detail` 读链路三分支（raw 读：`null`→查库回填 / `""`→NOT_FOUND / 非空→反序列化直返）；DB 未命中分支先 `set(key, "", SHOP_NULL_CACHE_TTL)` 再抛 NOT_FOUND；新增常量 `SHOP_NULL_CACHE_TTL = 2分钟` |
| `constant/KeyManage.java` | SHOP_INFO desc 更新：注明空值缓存复用本 key（空串标记，短 TTL）；template 与无默认 TTL 契约不变 |
| `test/.../shop/ShopCacheIntegrationTest.java` | 改造 `detailOfMissingShopThrowsWithoutCaching`（原断言"不写缓存"锁的是 M2.3 刻意保留的问题行为）→ 新语义：miss 抛 NOT_FOUND + 标记值为空串 + `0 < TTL ≤ 120`；新增 `emptyMarkerAbsorbsRepeatedMisses`：`@MockitoSpyBean`（本项目首次引入）spy ShopMapper，同一无效 id 连调两次均 NOT_FOUND，`verify(selectById, times(1))` 证明第二次被空值缓存吸收 |

读链路时序：`GET /api/shop/{id}` → `GET lk:shop:info:{id}`（raw）→ `null` → 查库：存在 → toVO → `SET key {vo JSON} EX 1800` → 返回；不存在 → `SET key "" EX 120` → 抛 NOT_FOUND → `""` → 空值命中，不查库，直接抛 NOT_FOUND → 非空 → `RedisJsonCodec.deserialize(raw, ShopVO.class)` 直返。

写链路时序：不变——`PUT /api/shop` 先更库再删缓存；`DELETE /api/shop/{id}` 先删库再删缓存；`requireExists` 失败时缓存操作不执行。

## 4. 验证记录（2026-09-03 本机实测）

### 4.1 常规验证

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy（Docker Desktop 冷启动后 `docker compose up -d mysql redis` 拉起） |
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 全量测试 | 同上 | **99/99 通过**（server 65 = M2.3 的 64 + 新增空值吸收测试 1；cache-starter 34/34 不变） |
| 新增测试明细 | `.\mvnw.cmd test -pl localink-server -am -Dtest="ShopCacheIntegrationTest,KeyManageTest" -Dsurefire.failIfNoSpecifiedTests=false` | **10/10 通过**：KeyManageTest×5（`lk:shop:info:1` 契约不变）；ShopCacheIntegrationTest×5（回填 TTL / 命中失效闭环 / **miss 写空串标记 0<TTL≤120** / **两次 miss 仅 1 次查库（spy 验证）** / 删除双清） |
| 存量回归 | ShopCrudIntegrationTest / ShopAuthIntegrationTest | 全过——update/delete 链路不动，CRUD 生命周期断言不受影响 |
| 启动冒烟 | `powershell -File scripts/smoke.ps1` | PING-OK: pong + CLEANUP-OK（进程树已杀、8086 释放） |

### 4.2 穿透证据：HTTP 轰炸 + `Com_select` 前后对比（铁律②"先暴露问题"）

流程：`POST /api/sms/code`（验证码取自应用日志）→ `POST /api/user/login` 取 token → 带 `Authorization` 头循环 20 次 `GET /api/shop/{不存在id}`，`docker exec localink-mysql mysql -uroot -plocalink123 -e "SHOW GLOBAL STATUS LIKE 'Com_select'"` 取前后差值。

| 阶段 | 轰炸 id | Com_select 基线→终值 | 差值 | 解读 |
|---|---|---|---|---|
| **修复前**（M2.3 jar）第一轮 | 998877665 | 10 → 31 | **+21** | 20 次请求**全部穿透到库**（+1 为 docker exec mysql 客户端自身开销，下同） |
| **修复前** 第二轮（换 id 复现） | 998877666 | 32 → 53 | **+21** | 两轮完全一致，证据稳定：换 id 攻击同样每次穿透 |
| **修复后**（M2.4 jar，同流程） | 998877667 | 304 → 306 | **+2** | 仅首 miss 查库 1 次写标记，**其余 19 次被空值缓存吸收** |

Redis 侧状态（修复后轰炸结束实测）：`GET lk:shop:info:998877667` → 空串（0 字节），`TTL` → 116s（0 < TTL ≤ 120 契约成立）；修复前轰炸后 `EXISTS` → 0（无任何防护，每次都是真穿透）。

**结论**：同一无效 id 轰炸 20 次的数据库 SELECT 从 20 次降到 1 次，穿透通道被空值缓存封死；对外行为不变（首尾响应均为 `{"code":40004,"message":"商户不存在"}`）。

### 4.3 排障记录

- 定向测试命令在 `-am` 连带构建的模块（如 localink-common）中无匹配测试会直接失败：`No tests matching pattern were executed`，需追加 `-Dsurefire.failIfNoSpecifiedTests=false`（M2.3 任务卡命令缺此参数，本次修正）
- 无其他排障

## 5. 学习清单

**核心知识点**
1. 缓存穿透 vs 缓存击穿 vs 缓存雪崩：穿透——请求的**数据在缓存和库都不存在**（无效 id/恶意构造），每次都打到库；击穿——**热点 key 过期瞬间**高并发全部打到库（M2.6/M2.7 解决）；雪崩——**大量 key 同时过期**（或 Redis 宕机）集体打到库（M2.5 解决）。三者的共同点是缓存层失效后流量直接砸向数据库，区别在于失效的**原因与范围**
2. 空值缓存原理：DB 未命中时不再"什么都不做"，而是把"不存在"这个事实本身缓存起来（空串标记 + 短 TTL），同一无效 id 的后续请求在 Redis 层就被挡住。本质是**用少量 Redis 内存换取数据库的查询免疫力**
3. 空值标记的实现要领：标记值必须能与"正缓存值"和"key 不存在"同时区分开。本项目利用 starter"String 原样写入"的约定用空串 `""`：`getString` 三分 null/""/非空，一次 raw 读覆盖全部分支，零额外网络往返
4. 空值 TTL 为什么必须短：空值是**猜测性缓存**——写入时"商户不存在"成立，但数据导入/修复随时可能推翻它。短 TTL 把误判窗口压到分钟级；同时不能太短，否则攻击流量在 TTL 过期后继续穿透。1~2 分钟是社区惯例折中
5. 空值缓存的固有缺陷：防"重复 id 轰炸"不防"随机 id 扫射"——每个新随机 id 都产生一个新空值 key，既挡不住查询打到库（首 miss 必穿透），还会被反向利用撑爆 Redis 内存。根治是布隆过滤器（M2.8）：在缓存之前判断"id 是否可能存在"，概率型数据结构，不存在则直接拒绝
6. `@MockitoSpyBean` spy 真实 Bean：不替换 Bean 行为、只旁听调用，集成测试里验证"第二次请求没查库"这类**调用次数断言**的关键手段——被测的是完整真实链路（拦截器/Redis/MySQL 全真），只有 mapper 被旁听
7. `Com_select` 计数验证法：`SHOW GLOBAL STATUS LIKE 'Com_select'` 是 MySQL 实例级 SELECT 累计值，前后差值即期间 SELECT 次数——不依赖任何日志/埋点，是验证"请求是否打到库"最硬的实证

**面试必问题**
1. "什么是缓存穿透？怎么解决？"——请求的数据在缓存和库都不存在，每次都穿透到库。方案：① 空值缓存（未命中也缓存，短 TTL），简单有效但有内存被扫射的隐患；② 布隆过滤器前置拦截，不存在的一定不存在，但有误判率且删除困难；③ 接口层参数校验（id 格式/范围）挡掉明显恶意值。生产常见"校验 + 布隆 + 空值"组合
2. "空值缓存为什么要设置较短的过期时间？"——空值是猜测性缓存，数据随时可能被补录；TTL 长 → 补录后长时间查不到（业务故障），TTL 短 → 攻击流量周期性穿透。1~2 分钟是两边风险的平衡点，且空值 key 数量大，短 TTL 也让内存占用快速回落
3. "空值缓存有什么缺陷？怎么进一步优化？"——防重复 id 不防随机 id：新随机 id 首次必穿透，且每个 miss 都写一个 key，可被用来撑爆 Redis 内存。优化：布隆过滤器在缓存前拦截；对 key 数量/来源 IP 限流（ratelimit 模块已预留）；空值 TTL 再叠加随机抖动避免集体过期
4. "穿透、击穿、雪崩分别怎么防？"——穿透：参数校验/空值缓存/布隆；击穿：互斥锁重建（M2.6）或逻辑过期异步重建（M2.7），让失效瞬间只有一个线程查库；雪崩：TTL 加随机抖动（M2.5）错峰过期，多级缓存/集群高可用兜底。三者组合拳是完整的缓存防护体系
5. "你怎么验证'第二次请求没有查数据库'？"——三种手段：spy mapper 断言 `verify(times(1))`（自动化、进 CI）；MySQL `Com_select` 前后差值（端到端实证）；MyBatis/Druid SQL 日志（肉眼排查用）。测试里用 spy，演示里用计数器，各司其职

## 6. 下一步

M2.5 缓存雪崩：TTL 随机抖动——正缓存 30 分钟固定 TTL 的同类 key 批量回填后会同时过期，`SHOP_CACHE_TTL` 传入处成为改造点（M2.3 登记 SHOP_INFO 无默认 TTL 的伏笔在此兑现）。
