# 迭代任务卡：M3.10 幂等框架——@RepeatExecuteLimit 三级防护

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.10（B8 批次） |
| 分支 | `feature/m3-10-idempotent-starter` |
| 状态 | 已完成（2026-09-17） |

---

## 1. 目标

第四个 starter（idempotent-starter）实体化：把 M3.8 手工的"orderId 守卫"沉淀为通用注解式幂等——结果标记（Redis）+ 本地公平锁（Caffeine 承载 ReentrantLock）+ 分布式公平锁（复用 lock-starter）三级防护；秒杀消费端换装。lock-starter 顺带抽出 SpEL 解析与受检异常包装两个公共件（idempotent 依赖 lock 是架构唯一豁免，抽取正是为了兑现这条豁免的价值）。

## 2. 设计取舍

- **三级防护流程**：标记快速路径（Redis GET，命中即回放/跳过——无锁无事务，最便宜）→ 本地公平锁（单机同 key 串行；锁内标记双检）→ 分布式公平锁（`runWithLock(FAIR, 看门狗)`；锁内标记三检）→ 执行 → 写标记。本地锁把同机并发挡在 Redis 之前，分布式锁把跨机并发串行化，标记让"第二次永远不用来"
- **结果标记存"结果"而非"success"标志**（超越参考项目）：fastjson2 序列化方法返回值，命中按方法返回类型反序列化**回放**——真幂等（同输入同输出）；参考项目只存 "success" 字符串、命中一律抛异常，服务不了消费端。null 结果存 `"null"`（JSON 语义，与字符串 `"1"` 的 `"\"1\""` 天然区分）
- **onDuplicate 双策略**：SKIP（默认——消费场景：重复投递安静跳过、正常 ack）与 REJECT（交互场景：防双击抛 IDEMPOTENT_DUPLICATE(40007,"请勿重复提交")）。锁等待失败复用 40005
- **切面 @Order(-100)（幂等最外层）是正确性前提**：① 标记命中时事务都不开（重复消息连数据库连接都不碰）；② **proceed() 返回即事务已提交，标记写在提交之后**——回滚的执行永远不落标记，重投才会重试。顺序反了 = 提交前的标记挡住重试 = 丢单。这是本批最值得背下来的一条
- **markerTtl 默认 24h、≤0 不写标记**：不写标记 = 只挡并发不挡重复（参考项目 durationTime=0 的用法）；标记丢失/过期无害——消费端唯一索引兜底。分层：标记快路径 → 唯一索引终审
- **标记读写失败降级**：读失败视为未命中（继续走锁+执行，最多多执行一次由终审兜）、写失败仅日志（本次业务结果照常返回）——幂等框架自己不能成为新的故障源
- **本地锁 Caffeine 过期与持锁的理论竞态**：48h 过期 vs 秒级 waitTime，窗口不可能；即使锁对象被回收换新，最坏退化为并发进分布式公平锁串行——后两层兜住。公平模式（本地+分布式）对齐：排队有序防同 key 饥饿
- **公共件抽取**：`SpelKeyResolver`（表达式缓存 + 空值快速失败）、`AspectProceed`（受检异常载体）从 ServiceLockAspect 私有实现升为 lock-starter 公共 API，两个切面共用——消重的同时给 idempotent 铺路（架构文档"idempotent 可依赖 lock"的兑现）
- **@AutoConfiguration(after = LockAutoConfiguration.class)**：com.localink.idempotent 字母序先于 com.localink.lock，不显式排序则 @ConditionalOnBean(DistributedLock) 静默跳过——B5 教训的第二次应用（写进两个 starter 的注释）

## 3. 产出物

| 文件 | 说明 |
|---|---|
| idempotent-starter | `RepeatExecuteLimit`（name/key/waitTime/markerTtl/onDuplicate/leaseTime）、`DuplicatePolicy`、`LocalLockCache`（Caffeine 公平锁）、`RepeatExecuteLimitAspect`（@Order(-100) 三级防护）、`config/{IdempotentProperties,IdempotentAutoConfiguration}`、imports |
| lock-starter | +`SpelKeyResolver`、+`AspectProceed`（public）；`ServiceLockAspect` 重构改用（行为不变，lock 测试 9/9 回归零变化） |
| common | +IDEMPOTENT_DUPLICATE(40007) |
| server | pom +idempotent-starter；`createSeckillOrder` 换装（selectById 守卫 → `@RepeatExecuteLimit(name="seckill-order", key="#message.orderId()")`，CAS+唯一索引保留兜底）；KeyManage +2 文档条目 |
| 测试 ×6 | 顺序重复回放/并发恰一次/失败不落标记可重试/不同 key 独立/REJECT 模式/void 静默跳过+markerTtl=0 重复执行 |
| chore | B7 广播测试 cleanup 补删 Redis key（收尾时发现的残留） |

## 4. 验证记录（2026-09-17 本机实测）

- idempotent-starter 集成测试 **6/6**（直连 Redis；断言用增量式防跨用例计数器串扰——排障见下）
- 全量 **175/175**（169 基线 + 6，BUILD SUCCESS + 用例数双确认）
- **换装端到端零改动通过**：`duplicateMessageIsGuardedByIdempotentConsumer`（FIFO 尾随标记论证法）在注解化守卫下原样通过——标记路径接住了 M3.8 场景

**排障记录**：首跑 2 例失败——多个用例共用 `returning` 计数器，先跑的用例把基数抬高，绝对值断言（`assertEquals(1, count)`）被串扰。修复：全部改增量断言（`before+1`）。教训：**有状态的测试组件 + 多用例共享 = 断言必须相对化**（或每用例独立命名空间，二选一）

## 5. 学习清单

**核心知识点**
1. **幂等的三层递进**：标记（挡"重复"，最便宜但需 TTL/丢失兜底）→ 本地锁（挡"同机并发"，进程内纳秒级）→ 分布式公平锁（挡"跨机并发"，毫秒级）——每层解决不同形态的重复，层层收窄
2. **标记写入时机与事务提交的关系**：标记必须在事务提交后写（切面在事务外层保证）；"提交前落标记"是幂等设计的经典丢单 bug
3. **回放式幂等 vs 拒绝式幂等**：回放（同输入返回同输出——适合查询型/只读型重复）；拒绝（明确告知重复——适合创建型交互）；消费场景需要第三种：静默跳过。一个注解的 onDuplicate 承载三种语义
4. **at-least-once × 幂等 = effectively-once**：M3.7（消息框架）+ M3.8（手工守卫）+ 本批（通用框架）三步拼出这条经典公式；标记与唯一索引是它的两层落地
5. **公平锁的价值**：排队有序防饥饿——同 key 高频重试场景下，非公平锁可能让某个请求永远插不进队
6. **切面公共件抽取的时机**：第二个使用者出现才抽取（rule of three 的变体）——提前抽象是猜，滞后抽象是债

**面试必问题**
1. "怎么保证消息不被重复消费？"——at-least-once 不可避免重复 → 消费端幂等：标记快路径（Redis）+ 业务终审（唯一索引）；能讲"标记必须提交后写"的丢单反面案例
2. "为什么三级？一级不行吗？"——标记挡不了并发窗口（两个请求同时查标记都未命中）；加锁串行化后才有一致的"执行→标记"顺序；本地锁是分布式锁的减震器（同机并发不产生 Redis 往返）
3. "标记丢了怎么办？"——幂等框架自身的可靠性降级：读失败视为未命中（多执行一次由终审兜）、写失败不阻断业务；幂等是优化层不是正确性层
4. "本地锁为什么用 Caffeine 承载？"——锁对象也要过期回收（防 key 无限增长的内存泄漏）；Caffeine 的 get(key, mapping) 原子装载天然适合
5. "@RepeatExecuteLimit 和 @Transactional 谁在外面？为什么？"——幂等在外（-100 < 事务默认序）；命中跳过事务 + 提交后写标记，两个理由都指向正确性

## 6. 下一步

**M3.11 消费可靠性：延迟消息丢弃 + 失败回滚 Redis（回滚 Lua）+ 指数退避重试**——补齐 M3.8 留下的补偿时机学：定制 DefaultErrorHandler（重试耗尽才回滚）、producerTime 超龄丢弃（MessageEnvelope.timestamp 终于派上用场）。
