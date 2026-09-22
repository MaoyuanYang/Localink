# 迭代任务卡：M3.13 限流框架①——令牌桶 Lua + 滑动窗口 Lua

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.13（B11 批次） |
| 分支 | `feature/m3-13-rate-limit-lua`（基于 m3-12 顶堆叠，审阅顺序 m3-11 → m3-12 → m3-13） |
| 状态 | 已完成（2026-09-22） |

---

## 1. 目标

M3 一致性链路（扣减/幂等/回滚/对账）闭环后转入流量防线第一块：两个限流原语的 Lua 原子实现。
`localink-ratelimit-starter`（M0.4 空骨架）首次长出代码：令牌桶（突发友好+平均速率约束）与
滑动窗口（严格均匀）两种算法语义，供 M3.14 RateLimitHandler 按场景组装（IP/用户双维度）、M3.15 秒杀前置令牌复用。

## 2. 设计取舍

- **时间一律 Java 传入（Lua 内不用 TIME）**：脚本内取时间是非确定性命令，整脚本复制语义下有坑；
  状态（last/now）本就活在 Java 时钟下，ARGV 携带毫秒最直白——与 deduct/rollback 脚本的 ts 参数同一纪律
- **令牌桶惰性补充（lazy refill）**：不跑定时任务灌令牌，取用时按 `elapsed × rate` 现算并推进对齐点 last。
  **拒绝分支同样要写回 tokens/last**——对齐点不前进，下个请求会把同一段 elapsed 重复计息；
  补充封顶 `min(capacity, ...)`，否则空闲一小时后突发透支，桶就失去了"平均速率"的意义
- **桶状态用 Hash 双字段（tokens/last）而非 String JSON**：纯数字直存免序列化，测试可直接 HSET last
  回拨时间模拟流逝（免真实睡眠，测试快且稳）
- **滑动窗口拒绝不登记**：未放行的请求不占窗口——若拒绝也 ZADD，攻击者可用被拒流量填满窗口实现自锁
- **先清窗外再计数**：`ZREMRANGEBYSCORE -inf (now-window)`（排他区间，恰在起点的保留）→ ZCARD → 判定 → ZADD。
  member 用 UUID（nanoTime 在并发下有撞车风险，ZSet member 重复会覆盖导致漏计）
- **两算法并存而非二选一**：令牌桶适合"允许攒额度"的场景（如 API 配额），滑动窗口适合"任意时刻严格均匀"
  （如注册/短信接口防刷）；M3.14 场景配置里按场景选型，原语层都备好
- **TTL 策略内聚在实现里**：桶=补满耗时×2（下限 60s）——空闲桶过期重建即满桶，语义无损；窗=窗口长+60s——
  最后一次放行滑出后 key 自然消失。调用方不感知 TTL，原语层保证无孤儿 key
- **starter 依赖纪律**：只加 data-redis，直接用 StringRedisTemplate（依赖规则禁止依赖 cache-starter，
  故 KeyBuilder 不可用）——key 前缀 `lk:rl:tb:/lk:rl:sw:` 由 Properties 配置，KeyManage 登记文档条目
  （同 IDEMPOTENT_MARKER"文档对齐"先例）
- **返回携带观测值**：'1|remaining'/'0|0'——剩余额度给 M3.14 做 Retry-After 响应头/监控埋点留了口

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `lua/token_bucket.lua` | 惰性补充+封顶+两分支写回；'1\|remaining'/'0\|remaining' |
| `lua/sliding_window.lua` | 清窗外(排他区间)+计数+拒绝不登记 |
| `RateLimiter` / `RateLimitResult` | 门面接口（tryAcquireTokenBucket/tryAcquireSlidingWindow）+ 结果 record |
| `impl/RedisRateLimiter` | 脚本执行+key 前缀+TTL 策略内聚 |
| `config/RateLimitAutoConfiguration` | 两脚本 Bean + RateLimiter 装配（@ConditionalOnBean(StringRedisTemplate)） |
| `config/RateLimitProperties` | keyPrefix（lk:）+ enabled（M3.14 熔断口径预留） |
| pom | +data-redis、+test 依赖 |
| `constant/KeyManage`（server） | +RATELIMIT_STATE 文档对齐登记 |
| 测试 ×9 | 桶：容量耗尽拒绝/时间流逝补充/封顶不满溢/50 并发恰放行；窗：阈值拒绝/窗外成员清除/拒绝不占窗/50 并发恰放行；前缀+TTL |

## 4. 验证记录（2026-09-22 本机实测）

starter 模块 **9/9** 一次全绿；全 reactor **190/190**（181 基线 + 9；分模块 cache 48 / lock 9 / idempotent 6 / **ratelimit 9** / mq 4 / server 114），BUILD SUCCESS。

**关键断言**：50 线程 CountDownLatch 同发抢 capacity=10 / threshold=10，放行恰 10、无超发（Lua 单线程原子的实证）；回拨 Hash last 字段 2s 后 rate=5/s 补充且封顶 capacity；窗外成员被清除（ZSCORE 为 null）；被拒请求不进 ZSet。时间推进全部用状态回拨模拟，测试零真实睡眠。

## 5. 学习清单

**核心知识点**
1. **限流两算法的语义分工**：令牌桶=平均速率约束+允许突发（攒的额度可一次花），滑动窗口=任意窗口严格均匀（无突发透支）。选型看业务要"弹性"还是"公平"
2. **惰性计算模式**：不维护定时刷新，读取时按时间差现算（refill/on-demand）。省掉调度器，代价是状态里必须存"上次对齐点"——last 不推进就是重复计息 bug（本批最细的坑）
3. **Lua 里时间的确定性纪律**：脚本内 TIME 非确定，复制语义下各副本结果不同；时间参数从外部传入，脚本纯函数化——项目第三个 Lua（deduct/rollback/限流）一以贯之
4. **拒绝不占窗**：限流器的"记账范围"只该包含放行流量，否则被拒流量可以反向填充窗口形成自锁——和 M3.12"流水只记成功动作"同一思想
5. **ZSet 做滑动窗口的选型**：比"固定窗口计数"精确（无临界突变），比"队列存请求"省内存（只留窗口内）；member 唯一性是 ZSet 语义的前提（重复 member 覆盖=漏计）
6. **starter 的依赖纪律**：框架模块只依赖 common+自己的中间件 client，key 前缀自管——依赖图单向无环的代价就是不能图方便用兄弟模块的工具

**面试必问题**
1. "令牌桶和滑动窗口怎么选？"——平均速率 vs 严格均匀；突发容忍 vs 临界精确。秒杀下单用哪个？→ 突发本来就是秒杀形态，桶配"容量=峰值预算、速率=DB 承受均值"
2. "令牌怎么补充的？定时任务吗？"——惰性补充：取用时 elapsed×rate 现算，无调度器；追问"为什么要存 last"→对齐点语义+拒绝分支也要推进（重复计息坑，说明真写过）
3. "Lua 里为什么不用 redis.call('TIME')？"——非确定性命令+复制语义；时间外部传入脚本纯函数——能延伸到项目所有 Lua 的统一纪律
4. "滑动窗口为什么用 ZSet？"——ZREMRANGEBYSCORE 清窗外+ZCARD 计数天然匹配；对比固定窗口的临界双倍问题
5. "限流状态会不会把 Redis 打爆？"——空闲 TTL（桶=补满耗时×2、窗=窗口长+60s）自动清理；ZSet 只留窗口内成员

## 6. 下一步

**M3.14 限流框架②：RateLimitHandler**——场景化组装层：@RateLimit 注解或 HandlerInterceptor（IP+用户双维度取 key）、场景配置（短信/登录/下单各自算法与参数）、白名单/封禁、BaseCode 限流错误码。server 补 ratelimit-starter 依赖声明（M3.10 漏声明教训的前置检查）。M3.13 的 RateLimitResult.remaining 届时用于 Retry-After 头。
