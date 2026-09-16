# 迭代任务卡：M3.2 JMeter 压测暴露超卖 → 乐观锁修复

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.2 |
| 分支 | `feature/m3-2-jmeter-oversell` |
| 状态 | 已完成（2026-09-16） |

---

## 1. 目标

兑现 M3.1 埋下的实验叙事：用 JMeter 压测 V1 纯 DB 下单，实录两处竞态窗口的失败形态（超卖被 unsigned 兜住后的"异常风暴"、一人多单），再用最小改动（`AND stock > 0` CAS 守卫）把库存竞态修成优雅失败，并采集纯 DB 版的 QPS/RT 基线锚点供 M3.6 对比。一人一单竞态本迭代**刻意不修**，实锤数据留给 M3.5。

## 2. 设计取舍

- **CAS 守卫放 UPDATE 的 WHERE 而非 SELECT FOR UPDATE**：`SET stock = stock - 1 WHERE voucher_id = ? AND stock > 0`，影响行数=0 即竞态输家——原子条件更新，无显式加锁、无持锁窗口。备选悲观锁 `SELECT ... FOR UPDATE`：能防但行锁串行 + 事务持锁窗口长，吞吐崩；乐观锁是 V1 的最小改动修复
- **影响行数==0 抛 10004 而非 40000**：竞态输家是"预期内的失败"，对前端是可展示文案，与 M3.1 错误码语义化一脉相承
- **requireStock 快照读保留**：绝大多数库存已空的请求在读判即被挡掉（本迭代实测 191/200 个失败走此路径，连 UPDATE 都不用执行）；但它读的是快照、可能过期，**权威判定在 CAS**——两层是"快筛 + 终审"关系，不是冗余
- **不加 version 版本号列**：stock 本身就承载版本语义（>0 才允许扣），加版本号是多写一列多一次读，适合"多字段并发更新"场景，不适合纯计数扣减
- **一人一单不修**：实验二实锤同一用户 10 单，但唯一索引兜底（M4 分库分表前的索引设计）与分布式锁（M3.3/M3.4 框架）都还没就位，提前修会打乱演进顺序
- **JMeter 资产入库**：`scripts/jmeter/`（参数化 jmx + 造数/执行 sh + README），`results/`（token/JTL）gitignore——资产可复现，产物不留库
- **压测成功口径 = HTTP 200 且业务码 0**：断言写在 JMX 里；业务码细分（10004/10005）与系统异常以服务端日志 + DB 对账为准，不信 JMeter 单一视角

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `SeckillVoucherMapper.deductStock` | `AND stock > 0` 守卫，返回影响行数 |
| `VoucherOrderServiceImpl.seckill` | 影响行数==0 → 抛 SECKILL_STOCK_NOT_ENOUGH |
| 测试 ×2 | `deductStockGuardReturnsZeroWhenStockEmpty`（确定性：库存 0 时影响行数 0，不越界）+ `concurrentSeckillOnLastStockDeductsExactlyOnce`（8 线程 8 用户抢 stock=1：恰好 1 成功、7 个 10004、1 单、stock=0） |
| `scripts/jmeter/seckill-load.jmx` | 参数化压测计划（threads/rampup/loops/voucherId/tokensFile 全走 -J 属性） |
| `scripts/jmeter/prep-tokens.sh` | 造用户：发码 → Redis 取码 → 登录 → token CSV |
| `scripts/jmeter/run-load.sh` + `README.md` | 非 GUI 执行 + 成败统计 + 复现指引 |

## 4. 验证记录（2026-09-16 本机实测）

环境：JDK 21 / JMeter 5.6.3（非 GUI）/ 单实例 / Hikari pool=10 / MySQL 8（Docker）。

### 实验一：300 不同用户抢 stock=100（rampup 2s）

| 指标 | 修复前（V1 裸扣减） | 修复后（CAS 守卫） |
|---|---|---|
| 成功订单 | 100 单 / 100 distinct users / stock=0 | 100 单 / 100 distinct users / stock=0 |
| 优雅拒绝（10004） | 191 | 176 |
| **系统异常（HTTP 500）** | **9**（`MysqlDataTruncation: BIGINT UNSIGNED value is out of range in 'stock - 1'`，事务回滚） | **0** |
| 数字超卖 | 无（int unsigned 物理兜底，M1.1 建表伏笔回收） | 无 |
| JMeter QPS / Avg RT | 113.9/s / 827ms | 62.5/s / 2632ms |

- 越界异常实测样本：9 个请求在"快照读判 stock>0 → UPDATE 排队到行锁"之间跨越了库存耗尽时刻，扣减时 0-1 越界 → Spring `DataIntegrityViolationException` → 全局兜底 500"系统繁忙"。**失败形态是系统异常而非业务拒绝，监控告警 + 用户感知双输**——这就是把校验放最下层（DB 约束）的代价
- 修复后 500 绝迹：所有到达服务器的失败请求（176）全部拿到 10004 文案
- 修复后 Avg RT 反升（827→2632ms）：失败请求从"快照读即挡"推进到 UPDATE 阶段 + Hikari=10 连接池排队加深；单机纯 DB 的天花板可见，提升路径是 M3.6 Redis+Lua（此数据即对比锚点的一半）

### 实验二：同一用户 200 并发抢 stock=300（本迭代不修，留 M3.5）

- **同一 user_id 成功建立 10 张订单**（一人多单实锤）；190 个后到请求被 10005 干净拒绝；stock 300→290
- 机理：并发事务在彼此提交前执行 `selectCount` 都读到 0，全部通过"一人一单"校验；行锁串行只挡住了后到者（它们的 count 查询看到了已提交订单），先到的 10 个漏网

### 基线：400 用户抢 stock=10000（库存充足，纯成功路径）

- 400/400 成功、0 异常、stock 9600 → **QPS 88.8/s，RT min 138 / P50 1083 / P95 1428 / P99 1461 / max 1467（ms）**——纯 DB V1 基线锚点，M3.6 复用同脚本对比

### 全量回归

`./mvnw test`：**137/137 通过**（+2：CAS 守卫单测 + 8 线程并发回归）。

**排障/方法论记录**：
1. **rampup=0 建连风暴**：首跑 300 线程 0s 内瞬时建连，195 个 `HttpHostConnectException`（Tomcat accept 队列溢出直接拒连），rampup 调 2s 后降到 24 个（RT 升高时在途请求堆积仍会顶到队列）。教训：**压测结论只统计到达服务器的请求；rampup 要让建连分散，否则量的是 accept 队列不是业务**
2. **Git Bash + 中文 JSON 体 = GBK 乱码**：curl -d 里的中文按 GBK 发出，服务端 UTF-8 解析报 40001"请求体格式错误"（`Invalid UTF-8 start byte 0xb2`）。教训：脚本化请求体用 ASCII
3. **jar 被 Windows 文件锁锁死**：服务器运行中重打包报 `Unable to rename ...jar to ...jar.original`——旧 JVM 还握着 jar（javapath shim 已退出、PID 失效）。教训：重建前先按 8086 端口找真实 JVM 进程杀树（netstat 找 PID → `taskkill /F /T`）
4. JTL 列含逗号（failureMessage）会错位，awk 统计只信 true/false 两类 + 服务端日志对账

## 5. 学习清单

**核心知识点**
1. **check-then-act 的实测形态**：校验（快照读）与写入（当前读 UPDATE）之间有缝——实验一的 9 个越界、实验二的 10 张重复单都是掉进缝里的请求；@Transactional 保证全成全败（越界者回滚、没留脏数据），但不消除缝
2. **CAS 乐观锁**：把"还允许扣"的判定合并进 UPDATE 的 WHERE，影响行数即成败信号；与版本号 CAS 的适用差异（计数扣减 vs 多字段更新）
3. **unsigned 兜底 vs 应用层守卫的失败形态**：DB 约束拦得住数字（无负库存）但拦不住体面——异常风暴 500 vs 业务拒绝 10004；防线越靠下层，失败形态越糟糕，这就是"校验前移"的动机
4. **快照读 vs 当前读**：requireStock 读的是 RR 快照（可能过期，只配当快筛）；UPDATE 是当前读（行锁串行，配当终审）
5. **压测方法学**：rampup 与 accept 队列、成功口径断言（HTTP 200 + code:0）、客户端/服务端双侧对账、P50/P95/P99 采集
6. **性能瓶颈直觉**：QPS 88.8 的天花板来自"Hikari=10 × 事务时长 + Tomcat 线程排队"（Little 定律：在途数 = QPS × RT），后续 M3.6 把扣减挪出 DB、M3.8 异步化建单都是对这个瓶颈的逐级拆解

**面试必问题**
1. "你们超卖是怎么发现的？"——JMeter 300 用户抢 100 库存实录：数字超卖被 `int unsigned` 拦住（建表时选 unsigned），但暴露为 9 个 `BIGINT UNSIGNED out of range` 系统异常；修复后 500 归零、失败全部 10004
2. "乐观锁怎么写的？为什么不用版本号？"——`UPDATE ... AND stock > 0` + 影响行数判定；stock 自带版本语义；版本号适合多字段并发更新
3. "为什么不用 SELECT FOR UPDATE？"——悲观锁行锁串行 + 持锁窗口长吞吐崩；CAS 是最小改动；追问"CAS 失败率高怎么办"→ 库存场景失败者直接出局无需重试，且 M3.6 用 Redis Lua 把争抢移出 DB
4. "修复后 RT 为什么反而变差？"——失败请求推进到 UPDATE + 连接池排队加深；说明纯 DB 单机天花板（QPS 88.8 / P99 1461ms），引出演进路线
5. "一人一单怎么保证？"——实验二实锤同用户 10 单（并发事务彼此提交前都读到 count=0）；三层修复：唯一索引兜底（DB）/分布式锁（串行化）/Lua 原子判断（M3.5 实测对比）
6. "压测怎么设计才可信？"——rampup 分散建连（否则量的是 accept 队列）、口径双端对账（JMeter + 服务端日志 + DB）、充足库存测吞吐 / 稀缺库存测竞态分开跑

## 6. 下一步

**M3.3 分布式锁框架①：RedissonClient 封装 + 四种锁类型**（lock-starter 首个迭代）：@Bean 装配 RedissonClient、可重入/公平/读写四锁封装、命令式工具接口——为 M3.5 一人一单升级（分布式锁对比演示）与 M2.6 遗留的"自研简单锁误删问题"（SHOP_REBUILD_LOCK 无持有者标识）提供替换弹药。
