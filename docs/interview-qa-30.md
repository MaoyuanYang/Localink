# Localink 30 道核心自测题（面试弹药）

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-09-29 | M7-B 产出：从 53 张任务卡（含 M8 修复卡后为 54 张）的 239 道候选题中精选 30 道，向题少的主题（压测调优/数据层）倾斜；每题带答题要点、追问与出处任务卡 |

用法：遮住要点自测；要点里的**数字全部实测**，可直接引用。完整学习材料见各出处任务卡的「学习清单」。

---

## A. 秒杀与一致性（7 题）

### 1. 纯 DB 版秒杀为什么会超卖？
**要点**：两个事务并发执行 check-then-act（查库存→扣减）——事务 A 保证的是原子性，不保证并发可见性；普通 SELECT 快照读互相看不见对方的扣减，UPDATE 前的判断基于过期快照。实测 300 并发 500 请求超卖 9 单、库存被扣成负数（stock 无符号约束报错风暴暴露）。
**追问**：加 `@Transactional` 能防吗？——不能，隔离级别下并发写同热点行仍竞态（可串行化能防但吞吐不可用）。
出处：[m3-1](iterations/m3-1-seckill-db-order.md)、[m3-2](iterations/m3-2-jmeter-oversell.md)

### 2. 超卖的三代替法与取舍？
**要点**：① CAS（`... where stock>0`）超卖归零但高冲突重试风暴，QPS 88.8→62.5；② 分布式锁串行正确但吞吐 85.4、49 个等锁超时；③ Redis Lua 原子扣减——热点移出 DB，拒绝路径 Avg 113ms 快 20 倍、DB 写 300→100。**正确性问题的终点是 Lua + DB 唯一索引兜底的纵深防御**。
**追问**：为什么保留了 DB 侧 CAS 和唯一索引？——Redis 与 DB 是两个系统，Redis 故障/迁移窗口的最后一道防线。
出处：[m3-2](iterations/m3-2-jmeter-oversell.md)、[m3-6](iterations/m3-6-seckill-redis-lua.md)

### 3. 一人一单怎么保证？
**要点**：三层——Lua 内 SISMEMBER 快筛（热路径 1 次 Redis）、userId+voucherId 维度分布式锁（历史版本，后退役）、DB 生成列唯一索引 `uk_user_voucher_active` 兜底（消费端 DuplicateKeyException 幂等吞掉）。锁方案实测 QPS 85 vs 基线 88.8——锁用户不锁券（竞态资源是购买资格），且条件唯一索引让锁可退役。
**追问**：为什么唯一索引能取代锁？——数据库约束天然串行且与业务写同事务，违反即冲突即拒绝。
出处：[m3-5](iterations/m3-5-one-order-per-user.md)

### 4. Redis 扣减成功了、DB 建单失败了怎么办？
**要点**：统一回滚——逆增量 Lua（库存+1、出已购集合、流水翻恢复态）+ 指数退避重试（200ms×2 封顶 1s 共 4 次）→ 仍失败落 `lk_rollback_failure_log` 等对账补偿。**Redis 是资格账本、DB 是事实账本**，两本账通过对账收敛。
**追问**：回滚也失败呢？——失败表行龄告警（24h）+ 对账 Job 下一轮扫描补偿，人工兜底有数据可查。
出处：[m3-11](iterations/m3-11-consumer-reliability.md)、[m5-reconcile](iterations/m5-reconcile.md)

### 5. 消息重复消费怎么防？
**要点**：三级幂等——结果标记（Redis 记消息 UUID，事务提交后才写——先写标记后回滚是丢单 bug）、本地排队、分布式公平锁串行同键请求。at-least-once × 幂等 = effectively-once。**幂等不是防重投，是让重投"来了也白来"**——命中标记跳过业务但照常 ack。
**追问**：重复的两种出生地？——生产端重试（幂等生产者/PID）、消费端 ack 丢失（消费幂等）。
出处：[m3-10](iterations/m3-10-idempotent-starter.md)

### 6. 对账体系的账是怎么记的、差异怎么处理？
**要点**：扣减/一人一单/流水在**同一 Lua 原子完成**——账本天生平；Redis 流水 vs DB 订单**单向比对**（Redis 有 DB 无=欠单），差异复用统一回滚补偿（不写第二套补偿逻辑）；宽限期只护差异判定（2min），终态流水未恢复→裁决关单。实测压测后自动补偿 156 笔时序差。
**追问**：为什么单向不双向？——DB 有 Redis 无是正常终态（单已成、资格已释放），反向才是异常。
出处：[m3-12](iterations/m3-12-reconcile-log.md)、[m5-reconcile](iterations/m5-reconcile.md)

### 7. 延迟队列在这项目里扛了哪几件事？
**要点**：三个闭环——超时关单（下单+15m，条件闸门：已支付/已取消不动）、开抢预通知（beginTime−2min **活动级**一条任务，到期回查事实源圈名单群发——绝不用户级，十万订阅者=到期风暴）、取消/关单后订阅自动发券（库存回流→popMin 最早订阅者，原子防重发）。RDelayedQueue=ZSet 扛时间+List 扛顺序+后台线程搬运；分片消费、StringCodec 信封重投保证不丢。
**追问**：预通知的"订阅"什么时候用？——到期时才查名单，用户订阅只是名单数据不是任务。
出处：[m5-delay-close](iterations/m5-delay-close.md)、[m5-notify-stats](iterations/m5-notify-stats.md)

## B. 缓存体系（6 题）

### 8. 写操作为什么删缓存而不是更新缓存？
**要点**：并发写回填竞争——两个写请求的"更新缓存"乱序完成会留下旧值；删除是幂等的且把回填交给下一个读（cache aside）。顺序先更库再删缓存，不一致窗口=删后到下次读回填之间，配合延迟双删可再压缩。
**追问**：删除失败怎么办？——超时重试 + 反常路径由 TTL 兜底（本项目的热数据走逻辑过期另说）。
出处：[m2-3](iterations/m2-3-shop-cache-aside.md)

### 9. 穿透、击穿、雪崩分别怎么防？
**要点**：穿透（查不存在的 key）→ 布隆前置拦截 + 空值缓存短 TTL；击穿（热 key 过期瞬间打 DB）→ 互斥锁重建或逻辑过期；雪崩（大量 key 同时到期）→ TTL 随机抖动（基值+jitter）。本项目商户详情四级纵深：L1 Caffeine → 布隆 → L2 Redis 逻辑过期 → DB。
**追问**：布隆说"存在"就一定存在吗？——单向确定性：说不存在必不存在，说存在可能误判（fpp 1%，漏放由空值缓存吸收）。
出处：[m2-4](iterations/m2-4-null-cache.md)、[m2-5](iterations/m2-5-ttl-jitter.md)、[m2-8](iterations/m2-8-bloom-framework.md)

### 10. 击穿的两种方案怎么选？
**要点**：A/B 实测（M2.7）——互斥锁：首个请求查库重建其余等待，DB 恒 1 次查询但等待请求 P99 抬高（实测互斥 121.4ms vs 逻辑过期 86.6ms）；逻辑过期：不设物理 TTL、值带过期时间，过期后**返回旧值 + 异步重建**——DB 压力趋零、可用性换一致性（旧值窗口 ~30min+jitter）。本项目商户详情选逻辑过期（容忍旧值的读场景 + 大流量）。
**追问**：异步重建的线程池满了呢？——队列 200 + CallerRuns 回压，重建任务不丢。
出处：[m2-6](iterations/m2-6-mutex-rebuild.md)、[m2-7](iterations/m2-7-logical-expire.md)

### 11. 已有 Redis 为什么还要 Caffeine 本地缓存？Redis 挂了能撑多久？
**要点**：L1 命中零网络零 Redis（纯进程内，微秒级），M7-A 实测缓存命中路径 12310 QPS 的主力就是 L1；Redis 宕机实验（M2.10）——L1 兜底热 key 500→200 QPS 衰减、10s TTL 后有界失效（不是雪崩是梯度退火）。L1 容量 1000 条 10s TTL，定位是"挡最热的读"不是"替代 L2"。
**追问**：多实例 L1 一致性？——Kafka 广播失效（cache-invalidation topic）+ TTL 兜底双保险。
出处：[m2-10](iterations/m2-10-caffeine-local-cache.md)、[m3-9](iterations/m3-9-cache-invalidation-broadcast.md)

### 12. 缓存的 Key 怎么治理？
**要点**：KeyManage 枚举 = key 模板 + TTL + 用途说明的唯一登记处，KeyBuild 统一生成（前缀 `lk:` 可配）；秒杀三 key（stock/order/flow）同 `{voucherId}` hash tag——Redis Cluster 下同槽位保证 Lua 多 key 原子性。**Redis 不是垃圾场，key 是有登记的资产**。
**追问**：为什么不直接拼字符串？——散落的魔法字符串无法审计 TTL/用途/前缀，枚举即文档。
出处：[m2-2](iterations/m2-2-key-manage.md)

### 13. 缓存命中率怎么验证（而不是拍脑袋）？
**要点**：实测——M7-A 基线压测用 Latency vs elapsed 分离证明 L1 命中路径的 Redis 调用为 0（Redis 命令计数 100→0 的对照实验，M2.10）；布隆拦截效果用"不存在的 id 打不到 DB"断言。**每个缓存层都有自己的验证方法**：L1=Redis 计数、布隆=miss 路径断言、L2=逻辑过期旧值返回。
**追问**：命中率低怎么排查？——分层计数定位是哪一层没命中，而不是笼统"加缓存"。
出处：[m2-10](iterations/m2-10-caffeine-local-cache.md)、[m7-load-test](iterations/m7-load-test.md)

## G. 压测与调优（4 题）

### 14. 12000+ QPS 是怎么测的？同机压测数字可信吗？
**要点**：JMeter 5.6.3 非 GUI、1000 线程 60s 时长档、rampup=5s（防 accept 队列假数据）、断言打到业务码（HTTP 200 ≠ 业务成功）、错误率 0.007%、P99 172ms。同机口径**如实声明**（客户端与服务端争 CPU，数字偏保守）；分场景报数：缓存命中 12310 / 未登录拒绝 5333 / 令牌拒绝 1630 / 成交稳态 168——**绝不拿单场景数字当全站容量**。
**追问**：瓶颈在哪？——缓存路径 CPU、令牌路径 Redis 往返（WSL2 NAT 放大）、成交路径校验链 DB 查询，各有归因各有下一步。
出处：[m7-load-test](iterations/m7-load-test.md)

### 15. 调优过程中最反直觉的发现？
**要点**：Tomcat threads.max 200→400——缓存命中 +74%（纯 CPU 短请求受益），但未登录拒绝 **-64%**（3722→1329，P50 1ms / P90 844ms 双峰 = 调度饥饿）。CPU 饱和场景线程翻倍是负优化，最终回退 200，**反例数据完整保留在报告里**——有反例的调优才可信。
**追问**：那什么场景适合加线程？——IO 等待型（下游慢）加线程有效；本例请求快、纯 CPU，线程多了只剩切换开销。
出处：[m7-load-test](iterations/m7-load-test.md)

### 16. 连接池为什么调 30 不是 100？
**要点**：爆池时 waiting=122 是**排队信号**不是"池不够大"的证据——申请链路每查询毫秒级周转，30 并发连接的周转能力已数倍于流量；调 100 只是把排队从 Hikari 转嫁给 MySQL（max_connections=151，测试环境先挤爆——回归阶段实证，surefire 把测试池压回 10 才稳定）。池的合理大小起点 ≈ 核数×2，看等待数微调。
**追问**：怎么发现池不够的？——`SQLTransientConnectionException: total=10, active=10, waiting=122, timeout 3013ms` 三联征 + JMeter 侧 500。
出处：[m7-load-test](iterations/m7-load-test.md)

### 17. 压测场景怎么设计的？为什么没测"售罄"？
**要点**：三场景按链路纵深分层——40002（纯拦截器，零 Redis）、10007（含一次 Redis Lua，业务校验拒绝代表）、缓存命中（L1 为主）、成功路径两步流（申请令牌→立即下单，XFF 伪造绕 IP 限流）。**售罄 10004 无法大批量复现**：令牌一次性消费（GET+DEL）先于库存判断，每个请求需新鲜令牌而申请限流 3 次/分/用户——压测设计与防刷机制的碰撞，如实记录。
**追问**：造数怎么做的？——发码接口有频控（1 次/分/IP），改为 Redis 植码+正常登录链路，400 用户 40 秒。
出处：[m7-load-test](iterations/m7-load-test.md)、[m3-2](iterations/m3-2-jmeter-oversell.md)

## D. 数据层：雪花与分片（3 题）

### 18. 雪花算法讲一下，时钟回拨怎么办？
**要点**：64 位 = 1 符号 + 41 时间戳（毫秒，69 年）+ 10 机器位（1024 节点）+ 12 序列（单机 4096/ms）；趋势递增利于 B+ 树索引。回拨分档——小回拨（<阈值）自旋等待追平；大回拨拒绝发号抛异常（宁停不重）。实测坑：雪花低 12 位被 Feed score 复用做同毫秒唯一位（41+12=53 位账）。
**追问**：为什么不用 UUID？——无序主键页分裂、36 字符太宽。
出处：[m4-1-2](iterations/m4-1-2-snowflake-id.md)

### 19. workId 怎么分配？多实例会冲突吗？
**要点**：Redis Lua 原子"取号并标记"轮转分配——重启拿新号不复用（旧号的消息可能还在途中）；代价是号段会漏（可接受，1024 个够用）。生产演进方向：租约+心跳（宕机释放）。**workId 冲突的后果是 ID 重复**，所以宁可浪费不可复用。
**追问**：为什么不用数据库自增分配？——DB 单点 + 每次启动一次查询也行，但 Redis 已在依赖里且 Lua 原子更轻。
出处：[m4-1-2](iterations/m4-1-2-snowflake-id.md)

### 20. 为什么按 user_id 分库？按 orderId 查询怎么办？
**要点**：分片键选**最高频查询维度**——用户查自己的订单是主路径，user_id 分片让这类查询单库闭环且同用户订单事务落同库；orderId 查询（客服/对账场景）走 `lk_order_route` 路由表（orderId→库表位反查）+ 无路由广播兜底。表级按 voucher_id 再分两表分散热点券写。
**追问**：为什么不按 voucher_id 分库？——跨用户的券维度查询少，且会把同一用户订单打散到多库。
出处：[m4-3-5](iterations/m4-3-5-sharding.md)、[sharding.md](sharding.md)

## E. Feed / 搜索 / 热榜（4 题）

### 21. Feed 流怎么设计的？大 V 怎么处理？
**要点**：推拉结合——普通用户发帖事件驱动推到粉丝 ZSet 收件箱（`user:feed:{fanId}`，读时零计算）；大 V（fans≥1000 可配）**不推**（写扩散上界失控），读时 DB 拉模式；读端归并收件箱∪大 V 帖按 score 去重。收件箱 popMin 截断（1024）控制单用户存储。取舍：读多写少场景推模式换读延迟，大 V 单独拉模式防写风暴。
**追问**：取关后收件箱里的旧帖？——读端按当前关注关系过滤（写时清理代价高且不准）。
出处：[m6-feed](iterations/m6-feed.md)

### 22. 滚动分页为什么不用 offset？游标怎么设计？
**要点**：offset 深翻页时新插入导致重复/遗漏（区间滑动）；游标分页用"上一页最后一条的 score 做下一页上界"（exclusive：lastScore-1 防边界重复）。**同分陷阱**：ZSet 同 score 排序不稳定 → score 拼唯一位 = `毫秒<<12 | 雪花低 12 位`（53 位），与 SQL keyset、ES search_after 同构。
**追问**：为什么不拼 userId？——也可以，但雪花低位已天然唯一且不占 score 语义。
出处：[m6-feed](iterations/m6-feed.md)

### 23. ES 数据怎么同步的？为什么不双写？
**要点**：发帖/删帖 AFTER_COMMIT 发 Kafka `post-search-sync`（key=postId 同帖分区 FIFO，消息只带 postId+事件类型）→ 消费端查 DB 组装 → **upsert 天然幂等**。双写三罪状：任一失败不一致、双系统的事务无法共管、代码侵入。**DB 是事实源，ES 是可重建派生视图**（rebuildAll 删索引全量重灌兜底）。查询三段式：multi_match title^2 算分 + shopId 走 post_filter（保侧栏聚合全集）+ search_after 深分页；ik 索引 max_word/查询 smart 不对称（召回 vs 精确）。
**追问**：消息丢了怎么办？——fire-and-forget + rebuildAll 兜底；对账式增量校验是演进项。
出处：[m6-search](iterations/m6-search.md)

### 24. 热榜怎么设计？为什么全量重算而不是增量累计？
**要点**：score = (liked×5 + comments×3 + uv×1) × e^(-λΔt)，半衰期 72h（ln2/λ，即榜单记忆长度）；**定时全量重算**：行为事实已在事实源（点赞表/评论数/UV HLL），重算=读事实写派生——写端零挂点零漂移；ZINCRBY 增量方案的问题：衰减是时间函数非事件函数，不触发行为的旧帖分数永不衰减（或需每帖定时补偿衰减，写放大）。UV 用 HLL（12KB/键、误差 0.81%、不能列成员——够排榜用）。
**追问**：候选集多大？——近 7 天新帖 ∪ 现役榜帖，快照落 DB。
出处：[m6-hot](iterations/m6-hot.md)

## F. 风控与特色（3 题）

### 25. 敏感词过滤怎么做的？
**要点**：两级审核按信任分级——显性词库 HashMap Trie **同步初筛**：O(文本×最大词长) 与词库规模无关（DFA），end 标记防前缀漏判、跳干扰字符，命中即拒不落库（30001）；隐性词库 Kafka **异步复审**：命中驳回复用删帖链路删 ES。先发后审 vs 先审后发按信任分级（普通用户先发后审保体验，高风险先审后发）。
**追问**：为什么不用正则？——正则内部本是自动机但十万分支不可控；Trie 是显式 DFA。
出处：[m6-audit-sign-geo](iterations/m6-audit-sign-geo.md)

### 26. 签到为什么用 BitMap？连续签到怎么算？
**要点**：一人一月一 key，每天 1 bit——4 字节/人/月（vs 每天一条记录）；连续签到=BITFIELD 取位串从今天向低位数连续 1 的个数（今天在最低位），跨月续查上月末尾。**实测坑**：SETBIT 与 BITFIELD 位序相反（SETBIT 第 k 位=BITFIELD 返回值第 W-1-k 位），封装层翻正 + 非回文断言锁死（初版回文串测试侥幸通过掩盖了 bug）。
**追问**：签到数据能复用吗？——补签/月活统计都是位运算，但活跃好友这类需要交集的不行（BitMap 无成员语义）。
出处：[m6-audit-sign-geo](iterations/m6-audit-sign-geo.md)

### 27. 限流怎么做？令牌桶和滑动窗口怎么选？
**要点**：Lua 实现两种算法，@RateLimit 注解 AOP 场景化配置（IP/USER 双维度、阈值覆盖）；秒杀令牌申请 = IP 10 次/60s + USER 3 次/60s 滑动窗口（临界精确，拒绝不登记）；再前置一次性秒杀令牌（TTL 30s 覆盖式、消费 Lua GET+DEL）削峰。**滑动窗口精确临界但内存随窗口请求量涨；令牌桶容忍突发适合平滑场景**。
**追问**：限流挂了怎么办？——Redis 故障 fail-open 放行（可用性优先，降级不雪崩）。
出处：[m3-13](iterations/m3-13-rate-limit-lua.md)、[m3-15](iterations/m3-15-seckill-token.md)

## C. 异步消息（2 题）

### 28. 怎么保证消息不丢、不重？
**要点**：不丢三段——生产 acks=all+幂等生产者、broker 多副本、消费手动 ack（处理完才提交 offset）；不重由消费端幂等收敛（见第 5 题）。顺序：同键同分区 FIFO（seckill 按 orderId、ES 同步按 postId）。消费失败：指数退避重试→耗尽走 Recoverer（回滚资格）→失败表对账兜底。
**追问**：为什么 Kafka 不用 DLQ？——回滚+对账比死信重放更可控（死信堆积无 SLA）。
出处：[m3-7](iterations/m3-7-mq-framework.md)、[m3-11](iterations/m3-11-consumer-reliability.md)

### 29. 下单为什么用 sendSync 同步发送？
**要点**：**"等待的东西变了"**——异步化后用户等的不再是 DB 而是消息投递确认；sendSync（acks=all 阻塞 ~3ms）换"消息必达"的确定语义，失败立即可知可回滚，把不确定性挡在承诺之前。 orderId 预生成随消息下发，用户无感知等待。
**追问**：3ms 会不会拖慢链路？——对比同步建单的百毫秒级 DB 事务，3ms 是净赚（Avg -81% 的实测来源）。
出处：[m3-8](iterations/m3-8-seckill-async.md)

## H. 工程架构（1 题）

### 30. 为什么是单体 + 8 个 Starter，不是微服务？
**要点**：业务规模不需要分布式，且拆微服务会把学习成本花在运维而非组件深度上。单体内部做**框架组件化**：8 个 Starter（cache/lock/idempotent/ratelimit/mq/delay/id/search）经 AutoConfiguration.imports 按需装配、单向依赖禁止循环——保留微服务"边界清晰"的收益，免掉网络/部署/事务的分布式税。真要拆时 Starter 就是服务的天然切割线。
**追问**：Starter 之间怎么防止耦合？——只依赖 common（唯一例外 idempotent→lock），业务零侵入引入。
出处：[m0-2](iterations/m0-2-architecture.md)、[architecture.md](architecture.md)
