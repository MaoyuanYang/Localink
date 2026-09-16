# 迭代任务卡：M3.3 分布式锁框架①——RedissonClient 封装 + 四种锁类型

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.3（B2 批次，与 M3.4 同批实施） |
| 分支 | `feature/m3-3-m3-4-lock-starter` |
| 状态 | 已完成（2026-09-16） |

---

## 1. 目标

`localink-lock-starter` 首个迭代：RedissonClient 装配（自动配置）+ 四种锁类型（可重入/公平/读/写）的命令式工具，为 M3.5 一人一单升级与替换 M2.6 自研简单锁提供弹药。纯框架层，不接业务。

## 2. 设计取舍

- **RedissonClient 双声明互让**：cache-starter 在 M2.8 已按 `@Bean + @ConditionalOnMissingBean(RedissonClient.class) + @ConditionalOnBean(RedisProperties.class)` 声明了 RedissonClient（当时就为多 starter 共享埋了伏笔）。lock-starter 沿用同一模式、从 `spring.data.redis.*` 同参构建——两个 starter **各自独立可用**；同处一个应用时先装配者创建、后者自动退让（构建参数完全一致，谁先生效无功能差异）。备选：lock 依赖 cache（违反架构铁律"starter 不互相依赖"）、server 统一装配（starter 不再自洽）、双实例（浪费连接）——均否
- **命令式模板 API 而非暴露 RLock**：`runWithLock(key, type, waitTime, leaseTime, action)` 拿锁→执行→finally 释放一条龙，调用方拿不到锁对象、不可能忘记释放。备选：getter 式（`getLock(key)` 返回 RLock）会把 Redisson 类型泄漏进全部业务代码，且释放纪律全靠自觉——否
- **LockType→RLock 的映射收在 impl 私有方法**：枚举与接口层零 Redisson import，锁实现可整体替换（API 层稳定）
- **leaseTime 语义：null/非正数 = 看门狗续期，正数 = 固定时长**：看门狗（默认 30s 租期、每 10s 自动续）解决"业务执行超过预估时长→锁提前过期→他人闯入→原持有者释放时误删"的 M2.6 痛点；固定 leaseTime 适合时长可预估的短临界区（省续期开销）
- **unlock 前判 `isHeldByCurrentThread()`**：即使看门狗失效、锁被 Redis 过期清理，也不会释放别人的锁——这是 Redisson 与 M2.6 自研"DEL 无持有者校验"的本质区别之二
- **超时抛 `LOCK_TIMEOUT(40005)`（4xxxx 框架/通用段）**：抢锁失败是"框架层的预期内失败"，不是券务语义；文案"操作繁忙"可直接透出给前端
- **tryLock 等待中断的礼仪**：捕获 InterruptedException → `Thread.currentThread().interrupt()` 贴回中断标记 → 转 LOCK_TIMEOUT 退出（空吞中断会让线程池关闭流程卡死，见学习笔记《线程中断》）

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `LockType` | 四值枚举：REENTRANT / FAIR / READ / WRITE（READ/WRITE 共用同 key 读写锁） |
| `DistributedLock` | 命令式接口：Supplier 版 + Runnable 默认方法 |
| `impl/RedissonDistributedLock` | tryLock 双形态（看门狗/固定）+ isHeldByCurrentThread 守卫释放 |
| `config/LockProperties` | `localink.lock.key-prefix`（默认 `lk:lock:`） |
| `config/LockAutoConfiguration` | redissonClient（互让声明）+ distributedLock 装配，`AutoConfiguration.imports` 注册 |
| `BaseCode` | +LOCK_TIMEOUT(40005) |
| pom | lock-starter +redisson/+data-redis/+aop/+test；server 聚合 +lock-starter |

## 4. 验证记录（2026-09-16 本机实测）

集成测试 5 例（直连 localhost:6379，CountDownLatch 同步时序、不靠 sleep 概率）：

| 用例 | 断言要点 | 结果 |
|---|---|---|
| reentrantLockSupportsNestedAcquireOnSameThread | 同线程嵌套获取（内层仅 300ms 等待预算），完成后锁释放 | ✅ |
| mutualExclusionTimesOutThenSucceedsAfterRelease | 互斥 + 超时抛 40005 + **竞争者失败不影响持有者锁**（isLocked 仍 true）+ 释放后重试成功 | ✅ |
| fairLockAlsoMutuallyExcludes | 公平锁同样互斥 | ✅ |
| readLocksAreSharedBetweenThreads | 两读锁 3s 内**同时**进入临界区（latch=2） | ✅ |
| writeLockExcludesReadLock | 写锁持有期间读锁 200ms 超时 | ✅ |

全量回归：**145/145 通过**（137 基线 + 8 新增）；server 同时引入 cache/lock 两 starter，RedissonClient 单例无冲突（互让机制实证）。

**排障记录**：无（本迭代一次通过；M3.4 侧有一次测试设计返工，见其任务卡）。

## 5. 学习清单

**核心知识点**
1. **Redisson 可重入锁原理**：Redis Hash 结构——field 为"客户端ID:线程ID"，value 为重入计数；加锁/解锁各一段 Lua 保证原子；重入 +1、解锁 -1、归零 DEL + publish 唤醒等待者。"同线程可重入"不是魔法，是 field 匹配判断
2. **看门狗**：不指定 leaseTime 时默认 30s 租期，后台任务每 10s（租期/3）检查持有者还活着就续到 30s；进程崩溃后最多 30s 锁自动失效——兼顾"不会误过期"与"不会死锁"
3. **M2.6 自研锁的三宗罪 vs Redisson**：①无持有者标识（释放可能误删他人锁→isHeldByCurrentThread/field 校验解决）②无续期（业务超时锁过期→看门狗解决）③不可重入（同线程嵌套直接自锁死→计数解决）。面试讲"为什么不用 SET NX EX 自己写"就按这三条打
4. **四种锁的取舍**：可重入（默认，吞吐最高）、公平（排队有序但换锁开销大吞吐低，只用于强顺序需求）、读锁（共享，适合读多写少）、写锁（排他）；读写锁同 key 才互斥，本质是两把关联锁
5. **条件装配的复用**：`@ConditionalOnMissingBean` 让基础设施 Bean 具备"有则用、无则建"的互让语义，这是 Spring Boot 官方 starter 共享基础设施的标准姿势

**面试必问题**
1. "Redisson 加锁的底层结构？"——Hash + Lua + pub/sub 唤醒，field=客户端:线程，value=计数
2. "看门狗什么时候生效？怎么续期？"——不传 leaseTime 才启用；默认 30s 租期每 10s 续；传了固定 leaseTime 就没有看门狗
3. "锁过期了业务还没执行完怎么办？"——看门狗续期；或业务侧保证 leaseTime > 最坏执行时长（不推荐，预估不可靠）
4. "你们的锁为什么要释放前判断 isHeldByCurrentThread？"——防误删：锁可能已被过期清理并被他人持有
5. "公平锁什么时候用？"——需要严格排队（如秒杀按到达顺序）时，代价是吞吐下降；默认用非公平

## 6. 下一步

M3.4（同批已完成）：`@ServiceLock` 注解 + SpEL 解析 key + AOP + 超时策略——见 `m3-4-service-lock-annotation.md`。
