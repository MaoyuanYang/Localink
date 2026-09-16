# 迭代任务卡：M3.5 一人一单升级——分布式锁上业务 + 唯一索引兜底

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.5（B3 批次） |
| 分支 | `feature/m3-5-one-order-per-user` |
| 状态 | 已完成（2026-09-16） |

---

## 1. 目标

B2 造的分布式锁第一次开火：seckill 接入用户维度锁，与 M3.2 实验二实锤的"一人 10 单"做对照实验；补条件唯一索引做 DB 最后防线（兑现 database.md M1 埋的"取消后再抢"伏笔）；顺手替换 M2.6 遗留的 SHOP_REBUILD_LOCK 自研锁（还 M3.3 预告的债）。

## 2. 设计取舍

- **锁维度 = 用户，不是券**：一人一单的竞态是"同用户并发"，锁 `lk:lock:seckill:order:{userId}`——同用户串行、不同用户并行。备选按 voucherId 锁：所有抢同一券的人全部串行化，QPS 崩，且对一人一单过度保守——否。实测佐证：300 不同用户并发 QPS 85.4 vs 基线 88.8（用户维度锁几乎零损耗）
- **userId 藏在 ThreadLocal，注解 SpEL 用 `T()` 静态访问**：`@ServiceLock(key = "T(com.localink...UserHolder).get().id")`。切面天然在事务外（M3.4 的 @Order(0)），零结构改动。备选：命令式锁 + 拆双 Bean 解决自调用事务失效——结构改动大，且 seckill 方法签名不该为锁让步
- **唯一索引用生成列做"条件唯一"**：`active_flag = IF(voucher_type=2 AND status=1, 1, NULL)` VIRTUAL + `UNIQUE(user_id, voucher_id, active_flag)`。MySQL 唯一索引忽略 NULL——取消/关闭订单不占位（**允许再抢**，M1 预留语义）、普通券不参与一人一单（**允许重复领**）。备选：朴素二元唯一索引（M5.4 超时关单一上线立即翻车：用户永远无法再抢）、Postgres 式部分索引（MySQL 不支持）——均否
- **voucher_type 冗余进订单表**：初版索引只判 status，被回归测试 `repeatedClaimCreatesSecondOrder`（普通券可重复领）当场拦截——一人一单是秒杀券的规则，索引判据必须含券类型，而类型在 lk_voucher 表 → 下单时冗余写入。这次拦截正是既有测试资产的价值实证
- **三层防线**（与 M2 缓存三层同构）：requireFirstOrder 查询快筛（应用层，拦截已可见的重复）→ @ServiceLock 用户锁（串行化同用户并发，消除"查后插"缝隙）→ 条件唯一索引（DB 最后防线，锁失效时兜底）。插入处 catch `DuplicateKeyException` → 转译 10005 优雅拒绝
- **SHOP_REBUILD_LOCK 替换，key 不变**：同步重建 `runWithLock(原 key, REENTRANT, 3s, 看门狗, 双检+重建)` 替代自旋+SET NX；异步选举 `tryWithLock`（新增 API：立即尝试、占用返回 empty）替代 setIfAbsent，且加锁前先做新鲜度预检减少无谓锁请求。看门狗根治 M2.6"业务超时锁过期误删"；KeyBuilder 生成 key，治理不破
- **KeyManage 登记 SECKILL_ORDER_LOCK 仅作文档对齐**：注解值必须是编译期常量，无法引用枚举 → 实际 key 由 lock-starter 生成，登记的模板与实际逐字对齐（`lk:` + `lock:seckill:order:%s`），供 redis-cli 观测与"枚举即文档"

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `VoucherOrderServiceImpl` | +@ServiceLock（T() SpEL）、+voucherType 写入、+DuplicateKeyException→10005 |
| `VoucherServiceImpl.claim` | +voucherType=普通（索引判据完整性） |
| `lk_voucher_order` | +voucher_type 列、+active_flag 生成列、+UNIQUE uk_user_voucher_active、-idx_user_id（被最左前缀覆盖） |
| `DistributedLock`/impl | +`tryWithLock`（立即尝试，Optional 返回，"选一个 worker"模式，M5.3 复用） |
| `ShopServiceImpl` | 重建锁两路径换 Redisson（同步 runWithLock / 异步 tryWithLock+预检），自研锁退役 |
| `KeyManage` | +SECKILL_ORDER_LOCK 登记；SHOP_REBUILD_LOCK 注释更新 |
| 测试 ×5 | 索引 3 例（秒杀活跃冲突 / 取消不占位可再抢 / 普通券可重复领）+ 同用户 8 线程恰 1 单 + tryWithLock 选举 |

## 4. 验证记录（2026-09-16 本机实测）

### 单测/集成：150/150 通过（145 基线 + 5 新增）

### JMeter 对照实验（复用 B1 资产，同参数）

**实验二（核心）：同一用户 200 并发抢 stock=300**

| 指标 | M3.2 修复前（B1 实录） | M3.5 用户锁 + 索引 |
|---|---|---|
| 同用户订单数 | **10 单** | **1 单** |
| 失败构成 | 190×10005（后到者快照已见订单） | 150×10005（进锁后查见）+ 49×40005（等锁超 3s，排队串行化后排尾） |
| 系统异常 | 0 | 0 |

**实验一：300 不同用户抢 stock=100**：100 单 / 100 distinct users / stock=0，无超卖；失败 187×10004 + 13 连接被拒（accept 队列，与 B1 同性质）；**QPS 85.4/s vs 纯 DB 基线 88.8/s**——用户维度锁对不同用户并发几乎零损耗（不同 key 不争用）；Avg 1518ms / P99 2678ms。

**排障记录**：
1. **初版唯一索引被回归测试拦截**：`repeatedClaimCreatesSecondOrder`（M1.7 资产）要求普通券可重复领，索引只判 status 把普通券也锁死 → 引入 voucher_type 冗余 + 生成列判据。教训：**建约束前先把"约束波及的业务规则"枚举全**，一人一单是秒杀规则不是订单表规则
2. **concurrentExpiredKeyTriggersSingleRebuild 瞬时断言在 Redisson 下翻车**：异步路径 16 个排队选举任务依次"拿锁→预检→跳过→释放"，原实现每任务 Redis 操作更少、排干快；Redisson 每任务 3 次 RTT 放大了"重建完成 ≠ 队列排干"的竞态。修复双管齐下：实现层 tryWithLock 前加新鲜度预检（多数排队任务不再碰锁）；测试层改有界轮询断言"最终无泄漏"（意图本就不是"此刻必无锁"）
3. **deletedShopStillPassesBloomAndWritesEmptyMarkerWithShortTtl 全量套件偶发失败**：TTL 断言窗口 [115,150) 只有 >5s 停顿才会越界，单跑/复跑均绿——判断为全量负载下标记写入与断言之间被卡顿（Redisson 后台线程略增调度压力），记录观察不再复现，暂不改造

## 5. 学习清单

**核心知识点**
1. **锁粒度选择**：锁"竞态的共享资源"——一人一单的共享资源是"该用户的购买资格"（用户维度），不是"券库存"（券维度已被 M3.2 CAS 保护）。粒度过粗 = 无谓串行化
2. **MySQL 条件唯一索引**：唯一索引忽略 NULL + 生成列 = 部分唯一约束的 MySQL 等价物；冗余列（voucher_type）是跨表约束落地的常规手段
3. **三层防线同构**：快筛（便宜、可能过期）→ 串行化（正确、有成本）→ 兜底（绝对可信、失败形态要转译）。M2 缓存三层与 M3 一人一单三层是同一个架构思想
4. **自调用事务失效**：同类内 this.method() 不走代理 → @Transactional 失效；注解式锁在切面层包住整个代理调用，天然规避此坑（这也是选注解而非命令式的重要原因）
5. **异步选举模式**：tryWithLock 立即尝试 + Optional 返回，"多个触发者选一个干活"——缓存重建、延迟队列消费都会复用
6. **看门狗实战**：重建业务时长不可预估（DB 慢查询），watchdog 自动续期替代固定 10s TTL，M2.6"锁过期误删"理论痛点在真实代码里根治

**面试必问题**
1. "一人一单怎么保证的？"——三层：查询快筛 + 用户维度分布式锁 + 条件唯一索引兜底；JMeter 实录 10 单 → 1 单
2. "为什么锁用户不锁券？"——竞态共享资源是用户购买资格；锁券会让所有抢券人串行化，QPS 崩（可报实测：用户锁 QPS 85 vs 基线 88.8）
3. "唯一索引怎么处理取消后再抢？"——生成列条件唯一：NULL 不参与唯一约束；顺带普通券不参与一人一单也是它保证的
4. "锁和事务的顺序？为什么？"——锁在事务外（切面 @Order(0)）；否则释放锁时事务未提交，下个持锁者读旧数据。追问"自调用为什么失效"→ this 不走代理
5. "锁挂了怎么办？"——锁只是第二层：快筛仍在，唯一索引兜底转译 10005；防线纵深让单层失效不致事故
6. "看门狗解决什么？"——业务时长不可预估时的自动续期；M2.6 自研锁 10s TTL 在慢重建下会误过期被他人闯入

## 6. 下一步

**M3.6 秒杀 V2①：库存预热 Redis + Lua 原子扣减**——把"库存判定+一人一单判定+扣减"从 DB 行锁串行搬到 Redis 单线程原子执行（hash tag 同槽位），DB 只剩最终建单落库；用 B1 基线（88.8 QPS）与本迭代数据做对比锚点。
