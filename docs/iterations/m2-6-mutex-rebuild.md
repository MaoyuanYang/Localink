# 迭代任务卡：M2.6 缓存击穿①：互斥锁重建（自研简单锁）

| 字段 | 内容 |
|---|---|
| 迭代编号 | M2.6 |
| 分支 | `feature/m2-6-mutex-rebuild` |
| 状态 | 待用户学习确认 |

---

## 1. 目标

按铁律②"先暴露问题→再解决问题"：先构造**热点单个 key 过期瞬间**（`PEXPIRE` 3 秒后过期 + `curl --parallel` 50 并发轰同一 id），以 MySQL `Com_select` 差值 + `general_log` 事件计数为实证，暴露击穿问题（过期瞬间 50 个并发请求全部打到库——单点击穿，区别于 M2.5 的批量同时过期）；随后在 `detail` 的 miss 分支落地**互斥锁重建**：`SET NX EX` 自研简单锁保证失效瞬间只有一个线程查库回填，其余线程自旋等待 + 双重检查，空值写入同样在锁内。兑现 M2.1 在 `setIfAbsent` 上预留的伏笔（"互斥锁/幂等标记/一人一单都依赖这条原子语义"）。

涉及表：`lk_shop`（无变更）；涉及接口：`GET /api/shop/{id}`（对外行为完全不变，变化仅 miss 分支的并发控制）；涉及 Redis Key：复用 `lk:shop:info:{id}`（不变），新增 `lk:shop:rebuild:lock:{id}`（重建互斥锁，TTL 10s）。

## 2. 设计取舍

- **锁实现选 `setIfAbsent`（SET NX EX）+ DEL 释放，值固定 "1"，故意不防误删**：本迭代定位就是"自研简单锁暴露问题"——业务执行超过锁 TTL（10s）时锁已自动过期，持有者 finally 里的 DEL 会误删后来者的锁；也不做 UUID 线程标识 + Lua 校验删除（hmdp 参考项目的 SimpleRedisLock 路线）——那是自研锁的第二代，Redisson 看门狗续期 + Lua 防误删是 M3.3/M3.4 的演进内容，现在做了 M3.3 就没有"为什么存在"可讲
- **双重检查两处缺一不可**：① 循环顶双检——自旋线程每轮先查缓存，拿锁线程回填（含空值标记）后立即命中返回，不空转到抢锁成功；② 拿锁后双检——抢锁与上一次查缓存之间存在间隙，可能别的线程刚回填完释放了锁，拿到锁的线程再查一次缓存可避免冗余查库。两处合起来才有并发测试"16 线程 miss 恰好 1 次 selectById"的确定性结果
- **等待策略选 50ms 轮询 + 50 次上限（约 2.5s），不做无限重试**：无限递归重试（hmdp 参考方案）依赖锁 TTL 兜底，最坏自旋 10s——若 DB 持续异常（重建一直失败），Tomcat 工作线程会被自旋占满，整站不可用；上限超限抛 `SYSTEM_ERROR`（40000 系统繁忙）快速失败，是"缓存层故障不拖垮服务"的最小降级。备选否决：Redis 订阅/通知唤醒——机制重、引入新依赖，超出最小迭代
- **锁 TTL 10s**：覆盖常规重建耗时（一次 selectById 毫秒级）且留足余量；持有者 JVM 崩溃时锁靠 TTL 自愈（M2.1 学习清单预告过的"最小自愈手段"），自旋线程随后可抢到锁重建
- **空值写入也在锁内**：M2.4 空值缓存与 M2.6 击穿防护叠加——"不存在的热点 id"过期瞬间同样只允许一个线程查库写空值标记，其余线程循环顶双检读到空串后直接抛 NOT_FOUND，穿透与击穿两条通道同时封死
- **锁 key 独立登记 `SHOP_REBUILD_LOCK`，不复用 SHOP_INFO**：生命周期不同（10s vs 30min+抖动）、语义不同（互斥占位 vs 业务数据），按 Key 治理"新增 key 只允许在 KeyManage 登记"的契约走枚举登记，desc 注明自研简单锁的暴露点
- **锁释放放 finally**：重建路径必抛异常（DB miss 抛 NOT_FOUND）也可能异常（SQL 故障），锁不释放则自旋线程全部等到 10s TTL 超时才能重试——finally 保证任何出口都归还锁

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `constant/KeyManage.java` | 新增 `SHOP_REBUILD_LOCK("shop:rebuild:lock:%s", 10s, ...)`：商户缓存重建互斥锁，desc 注明 SET NX EX 抢锁 + DEL 释放、误删隐患留待 M3.3 |
| `service/impl/ShopServiceImpl.java` | 新增常量 `SHOP_REBUILD_LOCK_TTL=10s`、`REBUILD_RETRY_LIMIT=50`、`REBUILD_RETRY_INTERVAL_MS=50`；`detail()` 拆为三个方法：`resolveCached(raw)`（空串→NOT_FOUND / JSON→直返，原三分支的前两支）、`rebuildWithMutex(id, key)`（自旋+双检+抢锁+finally 释放，超限抛 SYSTEM_ERROR）、`loadAndCache(id, key)`（查库 + 正/空值回填，TTL 抖动逻辑不动）。写链路 update/delete 不动 |
| `test/.../shop/ShopCacheIntegrationTest.java` | 新增 `concurrentMissHitsDbExactlyOnce`：create 后不预热，CountDownLatch 开工门对齐 16 线程并发 detail → join 后断言 `selectById` 恰好 1 次（@MockitoSpyBean 计数）、16 个结果 name 一致、无异常、锁 key 已释放 |

读链路时序：`GET /api/shop/{id}` → `GET lk:shop:info:{id}`（raw）→ **非 null**：空串 → NOT_FOUND；JSON → 反序列化直返 → **null（miss）**：自旋循环 ≤50 次 { 双检缓存命中即返回 → `SET NX lk:shop:rebuild:lock:{id} "1" EX 10` → 成功：再双检 → 仍 miss → `SELECT lk_shop` → 存在：`SET key {vo} EX {30min+[0,10min)}` 返回；不存在：`SET key "" EX {2min+[0,30s)}` 抛 NOT_FOUND → finally `DEL lock`；抢锁失败：sleep 50ms 重试 } → 超限抛 SYSTEM_ERROR。

写链路时序：不变——`PUT /api/shop` 先更库再删缓存；`DELETE /api/shop/{id}` 先删库再删缓存。

## 4. 验证记录（2026-09-03 本机实测）

### 4.1 常规验证

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy |
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 全量测试 | 同上 | **101/101 通过**（server 67 = M2.5 的 66 + 新增并发击穿测试 1；cache-starter 34/34 不变） |
| 新增测试明细 | ShopCacheIntegrationTest | **7/7 通过**：回填 TTL ∈ [1795,2400) / 批量抖动 / 命中失效闭环 / 空值标记短 TTL / 空值吸收 / **16 线程并发 miss 恰好 1 次查库（新增）** / 删除双清 |
| 存量回归 | ShopCrud / ShopAuth / KeyManage 等 | 全过——update/delete 链路不动；KeyManage 新增枚举不破坏 template 契约断言 |
| 启动冒烟 | `powershell -File scripts/smoke.ps1` | PING-OK: pong + CLEANUP-OK（进程树已杀、8086 释放） |

### 4.2 击穿证据：热点 key 过期瞬间 50 并发前后对比（铁律②"先暴露问题"）

流程：短信登录拿 token → 创建 1 家热点商户 → GET 回填 → `PEXPIRE lk:shop:info:{id} 3000` 构造"过期瞬间"（3 秒后统一过期，等价加速，因果链不变：热点 key 失效→并发全 miss）→ 等 4s 确认 EXISTS=0 → `Com_select` 基线 → `curl --parallel --parallel-immediate` 50 并发 GET 同一 id → 终值对比。

| 阶段 | Com_select 差值 | general_log 应用侧 SELECT 计数 | 解读 |
|---|---|---|---|
| **修复前**（M2.5 jar，备份 pre-m2-6.jar） | 877 → 932，**+55** | （未开启） | 50 个并发请求**全部穿透查库**（差值含 mysql CLI 读数噪声若干），全部 200——单点击穿实证：失效瞬间无并发控制，每个 miss 请求都执行一次 `SELECT ... FROM lk_shop` |
| **修复后**（M2.6 jar） | 1646 → 1653，**+7** | **恰好 1 条** `SELECT ... FROM lk_shop WHERE id=?`（general_log TABLE 模式逐条抓取） | 50 并发只有抢到锁的 1 个线程查库，其余 49 个自旋+双检后命中缓存；全部 200 |

修复后补充观测：burst 结束后 `KEYS lk:shop:rebuild:lock:*` 为空——锁用毕即释，无残留；单测侧 16 线程 CountDownLatch 并发 miss 同样 `selectById` 恰好 1 次 + 结果一致 + 锁释放。

实验数据已清理：商户 DELETE（DB 0 行、Redis key 空）、general_log 已 TRUNCATE 并关闭、进程树杀净、8086 释放。

### 4.3 排障记录

- **Com_select 差值法的噪声底**：修复后 50 并发差值 +7 而非预期的 +1。连续空转读数实测：每次 `docker exec mysql -e "SHOW GLOBAL STATUS"` 自身贡献 +1（mysql CLI 连接内部 SELECT），高并发窗口内噪声被放大。改用 **general_log TABLE 模式**（`SET GLOBAL log_output='TABLE'; general_log='ON'`）逐条抓取应用发出的 SELECT——**恰好 1 条**，拿到 ground truth。教训：指标差值法有底噪时，用事件日志核对，不能拿含噪差值直接下结论
- **终端直发中文 JSON 报 40001 请求体格式错误**：Git Bash 终端把请求体按 GBK 编码发出，Jackson 解析失败。改用 `printf` 写 UTF-8 编码临时文件 + `--data-binary @file` 解决（M2.4/M2.5 用 SQL 插入绕开了此问题，本迭代经 API 创建暴露）

## 5. 学习清单

**核心知识点**
1. 缓存击穿的定义与定位：**单个热点 key 过期瞬间**，并发请求全部打到库——"热"是前提（普通 key 失效只有零星 miss 自愈）。与雪崩（M2.5：批量 key 同刻到期，面状）、穿透（M2.4：查不存在的 key，点状但可恶意构造）三者的成因与解法各不相同
2. 互斥锁重建的原理：`SET NX EX` 原子语义保证**只有一个客户端**能写入锁 key——抢到锁的线程独占"查库+回填"这段临界区，其余线程自旋等待；击穿的伤害（N 个相同查询同时压库）被压缩成 1 次查询 + N-1 次轻量轮询
3. 双重检查两处缺一不可：循环顶双检让自旋者"见好就收"（缓存一回填就返回，不去抢锁）；拿锁后双检防"抢锁间隙已被回填"的冗余查库——它是"并发 N 线程恰好 1 次查库"的确定性保证，也是所有懒加载+锁模式（单例 DCL、本地缓存加载）的通型
4. 自研简单锁的三大缺陷（本迭代故意暴露）：① 无持有者标识——业务超时后 DEL 误删他人锁；② 不可重入——同线程嵌套抢锁自死锁；③ 无等待通知——只能轮询自旋，浪费线程与 CPU。对应 Redisson 的解法：看门狗续期 + Lua 校验删除 / 可重入哈希结构 / 订阅唤醒（M3.3 演进）
5. 锁 TTL 与自旋上限是组合兜底：TTL（10s）防持有者崩溃后的死锁——锁自动过期即自愈；自旋上限（50 次 × 50ms）防 DB 持续异常时自旋线程无限堆积占满工作线程——超限快速失败抛降级。前者保"锁一定会消失"，后者保"等待一定有尽头"
6. 空值写入也要进锁：穿透防护（空值缓存）与击穿防护（互斥锁）正交组合——"不存在的热点 id"同样只查一次库，两条通道同时封死
7. 观测方法：Com_select 差值法适合趋势对比但**有客户端噪声**（mysql CLI 每次读数自身 +1）；general_log TABLE 模式能逐条抓到应用真实发出的 SQL，是拿 ground truth 的手段。`curl --parallel --parallel-immediate` 可在单进程内构造真并发（50 连接毫秒级同时发出），比循环起 50 个后台 curl 进程重叠度高得多

**面试必问题**
1. "什么是缓存击穿？和雪崩、穿透什么区别？"——击穿：单个热点 key 过期瞬间并发全打库（本项目：互斥锁重建 M2.6 / 逻辑过期 M2.7）；雪崩：批量 key 同刻过期或 Redis 宕机（TTL 随机抖动 M2.5 / 多级缓存 M2.10）；穿透：查不存在的 key（空值缓存 M2.4 / 布隆 M2.8）。能报实测数据（修复前 50 并发 Com_select +55 → 修复后 general_log 仅 1 条 SELECT）是加分项
2. "互斥锁重建怎么保证只有一个线程查库？"——SET NX EX 原子抢锁 + 两处双检：循环顶让等待者缓存一好就返回，拿锁后再查一次防抢锁间隙的冗余查询。追问"为什么不用 synchronized"——进程内锁管不了多实例部署，分布式场景必须 Redis/Redisson 级别的互斥
3. "拿不到锁的线程怎么办？"——自旋轮询（50ms）+ 次数上限（50 次）+ 超限降级（系统繁忙）；不做无限重试的原因：DB 持续异常时会占满 Tomcat 工作线程拖垮整站
4. "锁超时了业务还没执行完会怎样？"——锁已自动过期被别人抢走，原持有者 finally 的 DEL 会误删新持有者的锁，互斥被破坏——这是自研简单锁的暴露点；解法是持有者标识 + Lua 校验删除（只删自己的锁）+ 看门狗续期（Redisson 默认 30s 每过 1/3 续期），M3.3 落地
5. "互斥锁方案有什么代价？"——重建期间所有请求阻塞等待，吞吐下降、RT 抬升；适合一致性要求高的场景。吞吐优先的替代是逻辑过期：缓存永不过期 + 异步重建 + 旧值兜底直返（M2.7 主题，两者取舍对比实测后收尾）

## 6. 下一步

M2.7 缓存击穿②：逻辑过期 + 线程池异步重建——正缓存改"包装结构+逻辑过期时间、无物理 TTL"，逻辑过期后拿锁者丢线程池异步重建、其余请求直返旧值；互斥锁降级为 key 不存在时的同步回退路径。两代方案的"一致性 vs 吞吐"取舍用并发 RT 对比实测收尾。
