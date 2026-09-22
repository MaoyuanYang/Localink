# 迭代任务卡：M3.12 对账日志——Redis 流水（Lua hset）+ DB 对账日志表

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.12（B10 批次） |
| 分支 | `feature/m3-12-reconcile-log`（基于 m3-11 分支顶堆叠——M3.11 PR 待合入，审阅顺序 m3-11 → m3-12） |
| 状态 | 已完成（2026-09-22） |

---

## 1. 目标

兑现 M3.11 留下的最后一块数据地基：**每一次 Redis 资格变动都留下账目**。扣减 Lua 在同一原子动作里写流水 Hash（"Redis 扣了谁、扣之前多少、之后多少"）；建单事务同步落 DB 流水行；回滚成功落恢复行。M5.1 定时比对"Redis 流水 vs DB 订单"从此有数可比——补偿链的最后一环"对账"的前置依赖就位。

## 2. 设计取舍

- **流水与扣减必须同一 Lua 脚本**：对账要回答"Redis 扣了谁"，账本若在扣减之后另写（Java 二次 HSET），"扣了但没记账"本身就成新不一致点。Lua 单线程执行，HSET 与 INCRBY 同脚本即天然原子——账本与账目变动同生共死
- **traceId 独立生成而非复用 orderId（与 database.md"Lua 生成"的偏差，主动声明）**：语义上 traceId 标识"一笔资格的生命周期"（扣减→建单→或回滚，可多行共享），orderId 标识订单，两列各司其职。生成位置从 Lua 挪到 Java（`IdWorker.getId()` 预生成后 ARGV 传入）：Lua 内拼唯一大数受 double 精度与确定性约束，而"谁生成"不影响"扣减与流水同原子"这一核心目标。database.md 4.11 已同步勘误。旧格式消息（traceId 缺失）以 orderId 顶替落账，防御分支有测试覆盖
- **Redis 流水是"活账本"，DB 是"长账"**：flow Hash（field=traceId）只存该笔资格**最新态**（logType 1 扣减 → 2 恢复翻新同 field），TTL 跟随活动，活动结束 Redis 活账清空；DB 流水表是 append-only 明细（一动作一行）。对账分工：活动期内 Redis↔DB 互查（快），历史追溯查 DB（全）
- **扣减流水行落 DB 的时机 = 消费端建单事务内（不进请求热路径）**：M3.8 刚把 DB 移出秒杀请求路径，不能为记账加回去。订单与流水行同事务——"有流水无订单"的中间态噪声不存在；消息若彻底丢失，Redis 流水在而 DB 无行，恰是 M5.1 要抓的差异
- **回滚流水翻新仅在 key 存活时**：活动 TTL 已过则 Redis 活账整体清空，强行 HSET 会重建无 TTL 的孤儿 key——长账在 DB 恢复行，Redis 不欠这笔
- **`uk_order_log(order_id, log_type)` 唯一索引（本批新增 DDL）**：一单同一动作一行。消息重投时订单唯一索引与流水唯一索引同兜底，双写不可能；表 M1.1 建时未加，实装时补（M1.1 备注第 6 条"用到时再补"的兑现）
- **回滚入口签名扩展 `(voucherId, userId, orderId, traceId, source, detail)`**：三处调用方（REQUEST_SEND/CONSUME_EXHAUSTED/STALE_DROP）都握有消息，orderId/traceId 天然在手；business_type 由 source 映射（STALE_DROP→2 下单超时，其余→3 下单失败），source 本身写入 detail 留痕
- **Lua 返回类型 Long → String（'0|traceId|before|after'）**：账目（traceId/前后库存）要从 Lua 带回 Java 进消息、再到消费端落 DB。备选是 Lua 返回数组或 Java 回读 Redis，前者 RedisScript 泛型啰嗦、后者多一次 IO 且引入时序缝隙——管道符拼接最直白，失败路径仍返回单码

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `lua/seckill_deduct.lua` | 三 key（+flow）+ traceId 参数；HSET 流水与扣减同原子；成功返回 '0\|traceId\|before\|after' |
| `lua/seckill_rollback.lua` | 逆增量回滚 + 流水翻恢复态（key 存活时）；返回 '0\|before\|after' 携账目 |
| `constant/KeyManage` | +SECKILL_FLOW（seckill:flow:{%s}，hash tag 同槽） |
| `framework/seckill/SeckillStockCache` | +flowKey()；evict 扩为三 key |
| `config/SeckillScriptConfig` | 两脚本 resultType Long→String |
| `entity/VoucherReconcileLog` + mapper | M1.1 建表首次启用（12 表全数激活） |
| `mq/SeckillOrderMessage` | +traceId/stockBefore/stockAfter（record 组件追加） |
| `VoucherOrderService(Impl)` | 扣减解析账目→消息携带；建单事务落扣减行（含 messageId）；回滚落恢复行（traceId 缺失以 orderId 顶替） |
| `SeckillOrderConsumer/Recoverer` | 回滚调用传 orderId+traceId；建单传 envelope.messageId |
| `sql/localink.sql` + 本地库 ALTER | +uk_order_log(order_id, log_type) 唯一索引 |
| docs | database.md 4.11 勘误（traceId 口径+索引）；architecture.md ⑨回滚勘误（DEL→逆增量，M3.6 定案的迟到同步）；README 进度段 |
| 测试 ×2 新 + ×3 改 | 新：成功链路三层同 trace 落账 / 回滚翻流水+恢复行+幂等不双写；改：Lua 测试（String 返回+流水断言）、可靠性测试（恢复行断言+business_type 归类）、异步测试（重投不双写流水+traceId 顶替+messageId 携带） |

## 4. 验证记录（2026-09-22 本机实测）

全量 **181/181**（179 基线 + 2，BUILD SUCCESS；分模块 cache 48 / lock 9 / idempotent 6 / mq 4 / server 114）。环境备注：Docker Desktop 未启动，中间件三件套冷启动后 MySQL 执行 ALTER 加 uk_order_log（表空无冲突）。

**关键断言**：成功链路 Redis 流水与 DB 扣减行共享同一 traceId（跨层串联锚点）、账目数字逐位相等（before 10/after 9/change -1）、扣减行携带 messageId；重投后流水不双写（标记守卫+唯一索引双保险）；回滚后 Redis 流水 logType 翻 2、恢复行 business_type 按 source 归类（STALE_DROP→2 / REQUEST_SEND→3）、重复回滚不双写恢复行（Lua 返回"无需补偿"即短路）。

**排障记录**：①增量编译假阳性——`mvnw test-compile` 对已存在 class 的签名变更不重编，test 断裂被掩盖，`clean test-compile` 才暴露 5 处旧构造；②RedisCache Hash API 名为 `hashes()` 非 `hashOps()`，首次编译报符号缺失后修正。

## 5. 学习清单

**核心知识点**
1. **账本原子性原则**：记账动作与账目变动必须在同一原子边界内（Lua 脚本/DB 事务）。任何"先改账再补记"的设计都在制造新的不一致窗口——对账体系自己不能是差异来源
2. **三层账本的分工**：Redis 活账（快、随活动过期、最新态）↔ DB 流水（慢、永久、append-only 明细）↔ 业务单据（订单）。对账 = 相邻两层互相比对，任何一层单方面缺失/多余都是差异事件
3. **写路径的时机学**：流水落 DB 选在消费端事务内而非请求线程——热路径不加一次 DB 写；"哪个线程写"比"写什么"更早决定
4. **traceId = 生命周期 ID vs orderId = 实体 ID**：串联多动作的应该是生命周期（一次资格：扣减+建单+可能的回滚），不是某个实体。分布式追踪的 spanId/traceId 二分同构
5. **唯一索引是幂等的最后防线**：uk_order_log(order_id, log_type) 让"同单同动作只发生一次"成为 DB 级不变量，应用层守卫（标记）丢了也兜得住——与一人一单唯一索引（M3.5）同一思想在记账域的复用
6. **Lua 返回值的携带设计**：脚本不只回答"成没成"（码），还回答"成了什么"（账目）——返回字符串管道符拼接，失败路径保持单码向后兼容

**面试必问题**
1. "你说有对账体系，账是怎么记的？"——扣减与流水同一 Lua 原子写 Redis 活账；建单事务同写订单+扣减流水行；回滚成功翻 Redis 流水+落恢复行；traceId 三层串联。追问"为什么流水要和扣减同脚本"→账本原子性（账本是差异检测器，自己不能制造差异）
2. "Redis 流水和 DB 流水什么关系？"——活账 vs 长账：TTL 跟随活动、只存最新态；DB append-only 明细。活动结束 Redis 清空后对账退化为 DB↔订单比对
3. "扣减流水什么时候落库？会不会影响下单性能？"——消费端建单事务内，请求热路径零新增 DB 写；订单与流水同事务，天然无中间态
4. "消息重投流水会不会写两遍？"——三层防线：幂等标记（快路径）→ 订单唯一索引 → 流水 uk_order_log；DB 级不变量兜底
5. "traceId 和 orderId 为什么是两个字段？"——生命周期 ID vs 实体 ID；一笔资格可有多行流水（扣减+恢复）共享 traceId，M5.1 扫 trace 即还原完整生命周期

## 6. 下一步

**M3.13 限流框架①：令牌桶 Lua + 滑动窗口 Lua**——M3 一致性链路（扣减/幂等/回滚/对账）至此闭环，转入流量防线。令牌桶 refill 速率的 Lua 实现（时间差×速率累加、上限封顶）与滑动窗口（ZSet 清窗外成员+ZCARD 计数）是两个经典 Lua 练手；为 M3.14 RateLimitHandler 与 M3.15 秒杀前置令牌供弹药。
