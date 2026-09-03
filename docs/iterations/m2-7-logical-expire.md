# 迭代任务卡：M2.7 缓存击穿②：逻辑过期 + 线程池异步重建

| 字段 | 内容 |
|---|---|
| 迭代编号 | M2.7 |
| 分支 | `feature/m2-7-logical-expire` |
| 状态 | 已完成（PR #38 合入，2026-09-03 用户学习确认，roadmap 已勾选） |

---

## 1. 目标

M2.6 互斥锁把击穿压到了 1 次查询，但重建期间其余请求**自旋等待**（实测 +48ms RT）——一致性优先的代价。M2.7 换取舍方向：正缓存改为**"包装结构 + 逻辑过期时间、无物理 TTL"**（`LogicalExpiryEntry{data, expireTime}`），key 永不物理消失，"过期瞬间"这个击穿前提根本不存在；逻辑过期后**拿到锁的线程丢线程池异步重建，所有请求立即返回旧值**——可用性优先，短暂牺牲一致性。兑现 database.md 预告的商户缓存最终形态"String(JSON)+空值+逻辑过期"。同轮同热度实测两代方案的 RT / DB 查询数 / 新旧值差异，拿到"一致性 vs 可用性"的完整对比数据。

涉及表：`lk_shop`（无变更）；涉及接口：`GET /api/shop/{id}`（对外行为：过期窗口内允许返回旧值，其余不变）；涉及 Redis Key：复用 `lk:shop:info:{id}`（值格式变更）与 `lk:shop:rebuild:lock:{id}`（异步重建防风暴锁），**不新增 key**。

## 2. 设计取舍

- **逻辑过期格式：`LogicalExpiryEntry{data, expireTime}`，物理无 TTL**。expireTime = now + 30min + 随机[0,10min)（复用 M2.5 抖动常量作逻辑 TTL，批量回填的逻辑过期时刻依然错峰）。空值缓存**保留短物理 TTL 不变**——正缓存是有限集合（真实商户，常驻内存可控），空值集合是无限集合（随机 id 扫射），空值 key 无 TTL 会无限累积泄漏。这是"正缓存无物理 TTL、空值缓存有物理 TTL"不对称的根本原因
- **泛型 entry 反序列化用 fastjson2 `TypeReference` 直写在 Service，不改 cache-starter**：`RedisJsonCodec` 只有 Class 版 API，泛型需要 `Type`——框架层为一个业务场景扩 API 违背最小迭代；fastjson2 经 cache-starter 传递依赖可用，`SHOP_ENTRY_TYPE` 静态常量只建一次。等第二个泛型缓存场景（M2.10 双层缓存）出现再评估下沉
- **异步重建触发也做双检（实验驱动补强）**：首版实验实测 SELECT=4——"读到旧值 → SETNX"的间隙内，前一个重建者可能已完成并释放锁，迟到的赢家会再触发一次幂等重建。修复：异步任务**拿到锁后先复查 entry 新鲜度**，已新鲜直接跳过（M2.6 双检思想搬到异步路径），复测收敛到恰好 1 条。残余窗口（两任务都在对方写入前过了复查）概率极低且重建幂等
- **线程池 `cacheRebuildExecutor`：ThreadPoolTaskExecutor Bean（core=2, max=4, queue=200, keepAlive=60s），拒绝策略 CallerRuns**：单实例热点重建量级下绰绰有余；极端堆积时退化为调用线程同步执行（等效回到 M2.6 行为），任务内 finally 释锁不受影响，`execute` 永不抛拒绝异常，无需额外释锁分支。备选否决：静态 `Executors.newFixedThreadPool`（hmdp 参考）——生命周期不随容器优雅关闭、参数不可调；DiscardPolicy——任务被丢时锁在任务内 finally，需额外释锁分支
- **互斥锁保留为 miss 回退路径**：逻辑过期的前提是"数据已在缓存"——key 不存在（冷启动/更新删除后）时没有旧值可兜底，退回 M2.6 的同步互斥重建，回填后即具备逻辑过期保护，**无需显式预热**。旧格式存量数据（M2.6 裸 ShopVO JSON）解析后 expireTime==null，同样当 miss 走重建覆盖，一次性平滑升级
- **update/delete 链路不动（遗留点）**：仍"先更库再删缓存"。删除后下次读 miss 走互斥锁同步重建——短暂失去逻辑过期保护，高并发写场景可演进为"标记逻辑过期（把 expireTime 改为过去）代替删除 key"，保留热点保护的同时让下次读异步拉新，留待后续迭代

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `framework/cache/LogicalExpiryEntry.java` | 新增：`{T data; LocalDateTime expireTime;}` 泛型包装，fastjson2 序列化 |
| `config/CacheRebuildExecutorConfig.java` | 新增：`cacheRebuildExecutor` 线程池 Bean（2/4/200/60s/CallerRuns），线程名前缀 `cache-rebuild-` |
| `service/impl/ShopServiceImpl.java` | `detail()` 读链路整体切换（四分支：null→互斥重建 / 空串→NOT_FOUND / entry 未过期→直返 / entry 过期→异步重建+旧值直返）；新增 `parseEntry`（TypeReference 解析+异常兜底）、`triggerAsyncRebuild`（SETNX 抢锁→任务内复查新鲜度→重建→finally 释锁）、`resolveFilled`（自旋双检的 entry 版）、`entryOf`（写入带抖动逻辑过期时间的 entry）；`loadAndCache` 改名 `loadAndCacheLogical`（正缓存写 entry 无物理 TTL、空值写空串短物理 TTL）；常量 `SHOP_CACHE_TTL(_JITTER)` 更名 `SHOP_LOGICAL_TTL(_JITTER)`；`@Slf4j` 记录异步重建失败 |
| `constant/KeyManage.java` | SHOP_INFO desc 更新为逻辑过期契约（entry 格式、无物理 TTL、空值保留短物理 TTL）；template/null-TTL 不变 |
| `test/.../ShopCacheIntegrationTest.java` | **改造 2**：`detailMissFillsCacheWithLogicalExpiry`（TTL==-1 + entry 格式 + expireTime∈[now+1795s, now+2400)）、`batchBackfillLogicalExpiriesAreJittered`（批量逻辑过期时刻落区间且 distinct≥2）；**新增 3**：`freshEntryServedDirectlyWithoutDbHit`（未过期直返 0 次查库）、`expiredEntryServesStaleAndRebuildsAsync`（旧值直返→轮询至重建完成→新值，查库恰 2 次=回填+重建）、`concurrentExpiredKeyTriggersSingleRebuild`（16 线程并发读过期 entry：旧值兜底+重建恰 1 次+锁释放）；其余 5 个存量测试（命中失效闭环/空值×2/删除双清/M2.6 并发 miss）原样通过 |

读链路时序：`GET /api/shop/{id}` → `GET lk:shop:info:{id}`（raw）→ **null**：互斥锁同步重建（M2.6 回退），写入 entry（无物理 TTL）→ **空串**：NOT_FOUND → **entry JSON**：expireTime > now → 直返 data（0 次查库）；expireTime ≤ now → `SETNX lk:shop:rebuild:lock:{id}` → 成功：线程池任务{复查 entry 新鲜度→未新鲜才查库→写新 entry→finally 释锁}，失败：不动作 → **无论成败立即返回旧 data**。

写链路时序：不变——`PUT /api/shop` 先更库再删缓存；`DELETE /api/shop/{id}` 先删库再删缓存。

## 4. 验证记录（2026-09-03 本机实测）

### 4.1 常规验证

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy |
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 全量测试 | 同上 | **104/104 通过**（server 70 = M2.6 的 67 + 新增 3；cache-starter 34/34 不变） |
| 定向明细 | ShopCacheIntegrationTest | **10/10 通过**：miss 回填 entry+TTL-1+区间 / 批量逻辑过期抖动 / 命中失效闭环 / 空值标记短 TTL / 空值吸收 / **未过期直返 0 查库（新增）** / **过期旧值兜底+异步重建（新增）** / **16 并发过期重建恰 1 次（新增）** / 16 并发 miss 恰 1 次查库 / 删除双清 |
| 存量回归 | ShopCrud / ShopAuth / KeyManage 等 | 全过——update/delete 链路不动；KeyManage template/null-TTL 契约不变 |
| 启动冒烟 | `powershell -File scripts/smoke.ps1` | PING-OK: pong + CLEANUP-OK |

### 4.2 两代方案对比：同热度第 4 轮 50 并发实测（铁律②实证收尾）

流程（两方案完全对称：登录拿 token → 创建商户 → 回填 → **预热 3 轮 50 并发热身** → 构造"过期"状态 → general_log 开启 → 第 4 轮测量）：

| 维度 | A：M2.6 互斥锁（pre-m2-7.jar） | B：M2.7 逻辑过期（本迭代 jar） |
|---|---|---|
| 过期构造 | `PEXPIRE 3000` 物理过期瞬间 | `SET` 覆盖 entry 的 expireTime 为 2020 年（无物理 TTL 可 PEXPIRE） |
| 状态码 | 50 × 200 | 50 × 200 |
| RT avg（min/max） | **121.4ms**（83.9 / 133.1） | **86.6ms**（max 135.3） |
| 纯缓存命中基线（第 3 轮预热） | 72.9ms | —（B 过期瞬间 86.6ms 已近基线） |
| DB SELECT（general_log 逐条计数） | **恰好 1 条**（同步） | **恰好 1 条**（异步） |
| 返回内容 | 全部**新值**（自旋等重建完成） | **50/50 旧值**兜底直返 |

B 补充证据：DB 改为第三值 + entry 再过期 → 50 并发响应体 50/50 均为旧值（缓存里的 FRESH 值）、0 个新值 → burst 后单次 GET 返回第三值 = 异步重建完成、一致性自动恢复；entry 的 expireTime 重新变未来时刻（19:13:03）。

**解读**：A 比 B 高 ~35ms（121.4 vs 86.6），与 A 自身基线差 ~48ms（121.4 vs 72.9）同量级——这就是 49 个请求自旋等待（50ms 轮询 1~2 轮）的成本；B 过期瞬间 RT 几乎回到纯命中基线（86.6 vs 72.9，差值来自 SETNX 与 entry 解析），代价是窗口期内返回旧值。**一致性 vs 可用性的取舍，两张 RT 表和响应体新旧值统计就是全部答案**。

实验数据已清理：商户 DELETE（DB 0 行）、`lk:shop:*` key 清空、general_log 已 TRUNCATE 并关闭、进程树杀净、8086 释放。

### 4.3 排障记录

- **首版实验 B SELECT=4 而非 1**：50 并发下"读到过期 entry → SETNX 抢锁"存在间隙，前一个重建者完成并释放锁后，迟到的请求成为新赢家、再次触发重建——重建幂等无害，但"防重建风暴"不够干净。修复：异步任务拿到锁后**复查 entry 新鲜度**（双检思想搬到异步路径），复测收敛到恰好 1 条。这是实验驱动设计补强的直接案例：没有 general_log 逐条计数，这个缝隙不会被发现
- **冷 JIT 误导 RT 对比**：B 首测 RT avg 606ms 全面偏高，而 A 的历史数据 115.8ms 是第二轮 burst（已预热）——直接对比会得出错误结论。实测纯命中预热轮也要 390~410ms（冷），修正协议为"预热 3 轮 + 第 4 轮测量"，两方案同热度对比（121.4 vs 86.6）。教训：性能对比必须同热度，否则测的是 JIT/连接池预热度而不是代码
- **`mvnw clean` 销毁了对照 jar**：pre-m2-7.jar 备份在 target/ 下，修复后重建执行 clean 把它删了。改用 `git worktree` 从 main 检出 M2.6 代码到临时目录构建对照 jar，不动当前工作区。教训：对照产物不放构建目录
- **curl 多 URL 时 `-o` 只作用于首个传输**：50 个 URL 只配一个 `-o /dev/null` 会让其余 49 个响应体漏进 stdout 污染 `-w` 输出（M2.6 实验的"non200=41"假象即源于此）。修正：每 URL 配对一个 `-o`，RT 与响应体才干净可解析

## 5. 学习清单

**核心知识点**
1. 逻辑过期的本质：把"过期"从 **key 的生命周期**（Redis TTL）挪到 **value 的字段里**（expireTime）——key 永远存在（TTL=-1），击穿的前提"过期瞬间 key 消失、并发集体 miss"根本不存在。防护思路从"过期后怎么办"（M2.6 互斥）升级为"让过期不产生 miss"（M2.7）
2. 逻辑过期下的分工：读到过期 entry 的线程里，抢到锁的那个**触发**异步重建（查库→写新 entry→finally 释锁），其余线程和自己一样**立即返回旧值**——重建完全离线，用户零等待；互斥锁的职责从"串行化查询"（M2.6）变成"防重建风暴"（保证同一时刻只有一个重建任务）
3. 两代方案对比（本项目实测）：DB 压力同为 1 条 SELECT；差异在 RT（A 121.4ms 含 ~48ms 自旋等待 / B 86.6ms 近基线直返）与一致性（A 等新值 / B 旧值兜底，窗口=重建耗时）。选型：一致性敏感（库存、价格）用互斥锁；高可用热点（榜单、详情页）用逻辑过期
4. 空值缓存为什么不能跟着逻辑过期：正缓存是**有限集合**（真实商户数），常驻内存可控；空值是**无限集合**（任意不存在 id 都会写一条），无物理 TTL 的空值 key 会被随机 id 扫射无限制造——内存泄漏。"正缓存无 TTL、空值缓存短 TTL"的不对称是刻意的
5. 异步触发也要双检：读旧值与抢锁之间的间隙里，前一个重建可能已完成释锁——迟到赢家会重复触发重建。任务内复查 entry 新鲜度即可收敛（实测 4→1）。与 M2.6 的同步双检同源：**凡是"检查→行动"两步之间可能插入他人写入的场景，行动前都要再查一次**
6. 线程池配置思路：重建任务是轻量短任务（一次 selectById+一次 set），core=2/max=4/queue=200 对单实例热点绰绰有余；拒绝策略选 CallerRuns——极端堆积时退化为调用线程同步重建（等效 M2.6 行为，语义闭环），且任务内 finally 释锁不受影响，execute 永不抛拒绝异常
7. 泛型 entry 序列化：fastjson2 需 `TypeReference<LogicalExpiryEntry<ShopVO>>{}.getType()` 携带泛型信息（Class 版会丢失 T）；`static final Type` 只构建一次。框架 API 为业务场景让路还是业务适配框架——最小迭代原则下选后者
8. 逻辑过期的前提与代价：前提是数据已在缓存（本方案 miss 时互斥重建回填后即具备，无需显式预热；hmdp 参考项目需提前预热）；代价是一致性窗口（旧值 served 到重建完成）+ 内存常驻 + 重建失败时旧值无限兜底（DB 长期挂会一直 serving 旧数据——极限场景需配合熔断/降级）

**面试必问题**
1. "击穿的两种方案怎么选？"——互斥锁：一致性优先，重建期间请求等待（实测 +48ms），实现简单无前置条件；逻辑过期：可用性优先，旧值兜底零等待（实测 RT 近纯命中基线），但需数据已在缓存且接受一致性窗口。报得出自己两套实测数据是硬加分
2. "逻辑过期为什么能防击穿？"——key 物理永不过期，"过期瞬间并发集体 miss"这个击穿前提被消除；逻辑过期只触发**一个**异步重建任务，请求路径不碰库
3. "逻辑过期方案下用户会看到旧数据，怎么办？"——窗口=单次重建耗时（毫秒级）；读旧值的同时已在异步拉新；若业务强一致（如价格）就不该用此方案——回到方案选型的本质：按业务容忍度选一致性等级
4. "异步重建失败会怎样？"——任务内 finally 释锁+log.error，旧值继续兜底，下次读再触发重建自愈；极端情况（DB 长期不可用）旧数据无限期 serving，需熔断/降级兜底——主动讲出边界比只讲优点加分
5. "重建线程池怎么配？拒绝策略选什么？"——按任务重量级（一次主键查询）配小池子；CallerRuns 让堆积退化为同步执行而不是丢任务，保证重建一定发生、锁一定释放
6. "你怎么验证两个方案的真实差异？"——同热度协议（预热对齐）+ general_log 逐条计数（避开 Com_select 客户端噪声）+ 响应体新旧值统计（证明旧值兜底）+ RT 分布。方法论本身是加分项

## 6. 下一步

M2.8 布隆过滤器框架：Redisson RBloomFilter 封装 + 配置化注册——穿透防护的进阶（空值缓存只能拦截"打过的"不存在 id，布隆能在请求进缓存前就过滤掉"肯定不存在"的 id），与 M2.4 空值缓存形成两层防线。
