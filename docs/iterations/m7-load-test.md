# 主题任务卡：M7-A 全链路压测与调优（JMeter 三场景基线 → 参数级调优 → 优化前后对比报告）

| 主题 | M7-A（收尾阶段，已完成） |
|---|---|
| 分支 | `feature/m7-load-test`（一主题一分支一 PR） |
| 状态 | 已完成 —— 2026-09-28（基线三场景阶梯 + 两轮调优对比 + 287/287 回归绿） |

## 1. 目标

把简历与 PRD 承诺的性能数字（单机 QPS ≥ 2000、P99 < 500ms，PRD F-NF-01）从"目标值"跑成"实测真话"：复用与扩展 M3.2 的 JMeter 资产，对**拒绝路径**（未登录 40002 / 无效令牌 10007）、**缓存命中路径**（商户详情 L1/L2）做线程阶梯压测；对**成功路径**（申请令牌→下单两步流）复刻 m3-8 exp3 口径复测回归。基线之上做**配置与参数级**调优（业务代码零改动）：Tomcat 线程池、Hikari 连接池参数透出（ds_0 此前硬编码 10）、Kafka 消费并发对齐分区数，产出优化前后 QPS/RT 对比数据报告。

## 2. 主题内子任务

1. 压测资产扩展：`unauth-load.jmx`（40002）、`shop-cache-load.jmx`（缓存命中）、`seckill-flow.jmx`（令牌申请→下单两步流，随机 X-Forwarded-For 绕开申请接口 IP 限流）、`seckill-load.jmx` 参数化改造（queryToken/expectCode/duration/CSV recycle）；`run-load.sh` 重写（任意 JMX + 任意 `-J` 透传 + P50/P90/P95/P99 分位统计 + 可选 HTML 报告）；`prep-tokens-direct.sh`（Redis 植码绕过发码频控的快速造数）。
2. 基线压测：三场景 × 100/300/600/1000 线程 × 60s 阶梯 + 成功路径 400 用户每人一单。
3. 调优 T1：Tomcat `threads.max`/`accept-count`（默认 200/100）按基线瓶颈上调。
4. 调优 T2：`ShardingProperties` 新增 `primaryPool` 配置透出 ds_0 池参数（此前 `ShardingDataSourceBuilder` 硬编码 10/3000，yml `spring.datasource.hikari.*` 在 sharding 接管时不生效）。
5. 调优 T3：Kafka `spring.kafka.listener.concurrency` 1→3，对齐 seckill-order 3 分区（加速峰值排水；全局工厂生效，分区少的 topic 多余消费者线程闲置无副作用）。
6. 优化后复压全部场景 → 对比报告 + 全量回归 287/287。

## 3. 设计取舍

- **拒绝场景选 40002 + 10007 而非"售罄 10004"**：秒杀令牌一次性消费（Lua GET+DEL）且校验先于库存判断——每个 10004 请求都需要一枚新鲜有效令牌，而令牌申请接口限流 USER 3 次/60s，"售罄拒绝"无法大批量稳定复现；10007 路径含一次 Redis Lua（业务校验拒绝的代表），40002 路径纯拦截器（最轻路径）。这是压测设计与令牌机制的碰撞点，如实记录。
- **成功路径用两步流内联申请令牌**：令牌 TTL 30s，预生成再压会过期；JMeter 每线程「POST /token → 立即 POST /seckill」。申请接口 IP 维度限流 10 次/60s，400 并发必触——用随机 X-Forwarded-For 头伪造来源 IP 绕开（RateLimitIpExtractor 取 XFF 首段），不关服务限流开关，压测口径与生产防线共存。
- **调优边界=配置与参数级**：m3-8 归因的成功路径剩余瓶颈（校验链 2 次 DB SELECT、sendSync acks=all）属于业务演进（券详情缓存化等），留待后续主题；本主题只动 yml 参数与池参数透出。
- **冷/热两跑**：服务进程经 smoke 与首档预热后即为热身态（JIT、Hikari 池、Lettuce 连接均就绪），各档取热身态数字；关键档复跑确认稳定性。
- **造数走 Redis 植码**：发码接口 sms-send 限流 1 次/60s/IP，串行造 400 用户必然失败（首跑实证）；验证码本是明文 Redis String（KeyManage.SMS_CODE），管道批量 SET 后走正常登录链路，注册/登录行为与真实用户一致。
- **JTL 产物不入库、脚本入库**：延续 M3.2 约定——压测资产（jmx/sh）可复现入库，results/（jtl/csv/报告）gitignore。

## 4. 接口 / Redis Key / 压测场景矩阵

| 场景 | JMX | 请求 | 预期业务码 | 命中链路 |
|---|---|---|---|---|
| 拒绝-未登录 | unauth-load.jmx | POST /api/seckill-voucher/{vid}/seckill?token=fake（无 Authorization） | 40002 | TokenRefresh 空头短路 → LoginInterceptor 拒非 GET |
| 拒绝-无效令牌 | seckill-load.jmx | POST 同上 + Authorization（CSV 登录态） | 10007 | TokenRefresh HGET+续期 → 令牌消费 Lua GET+DEL 失败 |
| 缓存命中 | shop-cache-load.jmx | GET /api/shop/{1..10}（匿名） | 0 | L1 Caffeine（10s 内）→ 布隆 → L2 Redis 逻辑过期 |
| 成功路径 | seckill-flow.jmx | ①POST /{vid}/token ②POST /{vid}/seckill?token=新申请 | 0/0 | 限流 XFF 绕开 → 令牌发放 → 消费 → Lua 扣减 → Kafka 异步建单 |

Redis Key 复用既有：`lk:user:token:{token}`（登录态，压测中每请求续期）、`lk:seckill:token:{vid}:{uid}`（TTL 30s）、`lk:sms:code:{phone}`（造数植入）、`lk:shop:info:{id}`、`lk:seckill:stock/{order}/{flow}:{vid}`。

## 5. 产出物

| 文件/类 | 说明 |
|---|---|
| scripts/jmeter/unauth-load.jmx | 40002 拒绝场景计划 |
| scripts/jmeter/shop-cache-load.jmx | 缓存命中场景计划（shopId 1..10 随机） |
| scripts/jmeter/seckill-flow.jmx | 成功路径两步流计划（XFF 伪造 + JSON 提取令牌） |
| scripts/jmeter/seckill-load.jmx（改造） | queryToken/expectCode 参数化、duration 调度模式、CSV recycle |
| scripts/jmeter/run-load.sh（重写） | 任意 JMX + key=value 透传 + 分位数统计 + 可选 HTML 报告 |
| scripts/jmeter/prep-tokens-direct.sh | Redis 植码快速造数（绕过发码频控） |
| localink-sharding ShardingProperties.primaryPool / ShardingDataSourceBuilder | ds_0 池参数透出（默认值不变，向后兼容） |
| application.yml | T1/T3 + T2 调优参数（值以基线数据定） |

## 6. 验证记录（2026-09-28 本机实测）

环境：22 逻辑核 Win11 / JDK 21 / JMeter 5.6.3 非 GUI 单实例（`-Xmx4g`）/ 服务 `-Xmx2g` / MySQL8+Redis7+Kafka3.9 全部 Docker Desktop(WSL2) / **压测机与应用同机**。60s 时长档、rampup=5s（阶梯）/2s（flow 冲刺复刻 m3-8 口径），服务进程预热后即热身态。

### 6.1 基线（默认配置：Tomcat threads 200/accept 100，Hikari 10+10，Kafka concurrency 1）

| 场景（QPS=样本数/60s） | 100 线程 | 300 线程 | 600 线程 | 1000 线程 |
|---|---|---|---|---|
| 拒绝-10007（avg/p99 ms） | **1549** / 62 / 95 | 1518 / 187 / 301 | 1445 / 402 / 624 | 1332 / 713 / 2498（196 超时失败） |
| 拒绝-40002 | 3313 / 29 / 300 | **3722** / 77 / 828 | 2742 / 211 / 1957 | 1842 / 493 / 3700 |
| 缓存命中 | **4672** / 20 / 295 | 2796 / 101 / 1126 | 2415 / 228 / 1550 | 3966 / 232 / 1186 |
| 成功路径 flow-400 | 成功率 75%：94 个 Connection refused（accept 队列打满）+ 5 个 500（Hikari 爆池 `total=10, active=10, waiting=122`，3s 超时）+ 301 单全成功 | | | |

读法：三场景吞吐都在 100~300 并发见顶后回落、RT 线性上涨——CPU 饱和特征（Little's Law 实证：吞吐不涨而并发翻倍 ⇒ RT 翻倍）；10007 比 40002 慢 2.3 倍 = 每请求 2 次 Redis 往返在 Docker/WSL2 NAT 下的放大；基线 flow 直接暴露两个可调缺陷（拒连 + 爆池）。

### 6.2 调优过程

- **第一轮（threads.max=400）**：拒绝路径全面负优化（40002@300 3722→1329，-64%，p50 1ms/p90 844ms 双峰=调度饥饿）；缓存命中大赚（cache@100 4672→8146 +74%）；flow 反而 p99 10s 大超时。**结论：CPU 饱和场景线程翻倍是负优化，参数收益是场景依赖的**——按主口径（拒绝+全链路正确性）回退 threads=200，弃用该轮。
- **定稿轮（threads 200 + accept-count 1000 + Hikari ds_0/ds_1=30 + Kafka concurrency 3）**：

| 场景（最优档） | 基线 | 调优定稿 | 变化 |
|---|---|---|---|
| 拒绝-10007 @100 | 1549 / p99 95 | **1630** / p99 101 | +5%（Redis 往返主导，HTTP 层参数帮助有限，符合归因） |
| 拒绝-40002 @300 | 3722 / p99 828 | **5333** / p99 461 | **+43%，P99 -44%** |
| 缓存命中 @100 | 4672 / p99 295 | **10038** / p99 57 | **+115%，P99 -81%** |
| 缓存命中 @1000 | 3966 / p99 1186 | **12310** / p99 172 | **+210%，P99 -86%**（12310 QPS 峰值段 14244/s） |
| 成功路径 flow-400 | 成功率 75% | **800/800 = 100%**，零拒连零 500；400 并发排队后集中完成（①申请 avg 4793ms / ②下单 avg 3135ms，瓶颈=申请的 DB 查询 + 同机 CPU） | 正确性修复 |

- **Kafka T3 验证**：重启日志见 post-audit 3 分区各 1 消费者（concurrency=3 生效）；重启后对账 Job 自动补偿基线压测遗留的 156 笔时序差异——M5-A 对账体系在压测场景下的真实兜底。
- **m3-8 历史口径核对**：exp3-async-warm.jtl 用新统计精确复核 = 400 样本全成功、avg 234ms / p50 178 / p99 513——与 m3-8 记录（168.4 QPS / Avg 233 / P99≈500）吻合。**注意**：m3-8 的 168 是"无令牌机制（M3.15 之前）"的单步口径；M7-A 全链路含令牌申请，两步合计 800 请求 12.4s ≈ 65 req/s（400 并发突发），吞吐不可直接对比，面试口径以"演进史 168（异步化收益）+ 当前全链路 100% 正确率"分层表述。

### 6.3 简历数字落定（真话版）

| 简历口径 | 实测支撑 | 结论 |
|---|---|---|
| 单机 QPS 2000+ | 缓存命中 10038~12310、未登录拒绝 5333（令牌拒绝 1630 未达，如实标注） | ✅ 达成，口径="缓存命中与拒绝路径" |
| P99 < 500ms | cache p99 57~172、40002@300 p99 461、10007@100 p99 101 | ✅ 达成（1000 并发档除外，如实分档标注） |

### 6.4 全量回归

清扫压测数据（1500 个压测用户、压测券及订单、相关 Redis key）后：surefire 测试池上限 10（见排障实录 7）+ 全 reactor `mvnw test` = **287/287 全绿，BUILD SUCCESS**（各模块分布与基线一致，server 187/187）。压测数据污染的两个回归插曲见排障实录 8/10。

### 6.5 排障实录

1. **JMeter `RegexExtractor` 属性名大小写**：`refName`（驼峰）被静默忽略，提取结果落到空键名变量，下游 `${var}` 不替换、URL 带字面量 `${seckillToken}` 抛 URISyntaxException。JSR223 PreProcessor 打印 `vars` 发现 `[=pong, _g0=pong]` 定位；正确属性名是全小写 `refname`。JSONPostProcessor 同症状（其属性名要求也不同），统一换正则提取器。
2. **path 属性混用 `__P` 与运行时变量**：JMeter TestCompiler 静态替换与运行时变量注入的边界——跨 sampler 传 query 参数改用 HTTP Arguments 参数表（`Argument.value=${var}`）后稳定。
3. **prep-tokens 串行造数触发发码限流**：sms-send 场景 1 次/60s/IP，第 2 个用户即失败；验证码本是明文 Redis String（KeyManage.SMS_CODE），管道批量 SET 植码后走正常登录（`prep-tokens-direct.sh`），400 用户约 40s 且注册/登录行为与真实一致。
4. **400 并发 rampup≤2s 冲刺 accept 拒连**（m3-2 教训在新线程规模复现）：94 个 `HttpHostConnectException`；定稿 accept-count=1000 后同参数复测零拒连。
5. **Hikari 爆池三联征**：`active=max=10`、`waiting=122`、connection-timeout 3s → SQLTransientConnectionException → HTTP 500；根因是 ds_0 池在 ShardingDataSourceBuilder 硬编码、yml 的 `spring.datasource.hikari.*` 在 sharding 接管时不生效（配置双轨暗坑）——透出 `localink.sharding.primary-pool` 后调 30，500 归零。
6. **threads.max=400 的场景依赖双刃剑**：纯 CPU 短请求（缓存命中）吞吐 +74%，含调度的混合负载（40002）-64% 且 P50/P90 双峰（调度饥饿）。调优实验保留了完整反例数据——"无脑调大线程池"的实证反驳。
7. **测试挤爆 MySQL 151（M6 老坑在 30+30 池下复发）**：多测试上下文切换峰值 ×60 连接/上下文 → `Too many connections` 拒绝建池；surefire `systemPropertyVariables` 把测试 JVM 的池压回 10（系统属性优先于 yml，不影响压测/生产配置）。
8. **登录 token 池闲置 30min 过期**：TokenRefreshInterceptor 的续期只保活跃期，复压间隔超 30 分钟后 23.7% 样本变 40002——拒绝场景数据作废重跑；压测脚本与被测数据的生命周期要对齐。
9. **JTL 统计错列**：失败样本的 responseMessage 含逗号会推开第 8 列 success；精确统计按 sampler 名 grep 后单列计算。
10. **压测数据污染回归**：① 中断的测试运行残留固定夹具 id=9201 主键冲突 → DELETE 后过；② 预通知测试断言"12 秒送达"失效——压测留下的 1000+ 用户把圈名单基数放大（`预通知已群发: audience=1000`，群发耗时 13s 超断言窗口）。教训：**压测是侵入性操作，收尾必须清扫数据**（用户/订单/券/Redis key），且测试断言窗口对环境数据规模敏感。
11. **清扫 SQL 撞分片表逻辑名**：首轮清扫写的 `DELETE FROM localink.lk_voucher_order`（逻辑表名）在主库不存在——mysql 多语句 -e 在该条报错中断，但其输出被 `2>/dev/null` 部分掩盖、且首条用户 DELETE 已执行，造成了"清扫完成"的误判；收尾复核时才发现订单/对账日志残留在 **ds_0/ds_1 各自的物理分片表**（`lk_voucher_order_0/1` 等，共 131 单 + 1213 条日志），按物理表逐表 DELETE 后归零。教训：分片环境下手工清数据要按物理表名来，且**清扫必须复查计数而不是相信命令返回**。

## 7. 学习清单

**核心知识点**
1. **压测方法论**：冷/热两跑（JIT、连接池预热差异巨大）；rampup 不为 0（否则量的是 accept 队列）；时长档优于请求档（吞吐稳态可比）；断言打到业务码（HTTP 200 不代表业务成功）；同机压测口径必须声明（客户端与服务端争 CPU，数字偏保守）。
2. **Little's Law 现场版**：吞吐 = 并发 / RT。并发翻倍吞吐不涨 ⇒ RT 必翻倍 ⇒ 已到容量顶；三场景在 100~300 并发见顶回落 + 上下文切换开销，是 CPU 饱和的标准曲线。
3. **线程池调优的场景依赖**：CPU-bound 快路径，threads 翻倍 = 切换风暴（40002 -64%）；纯计算短请求反而受益（cache +74%）。参数没有全局最优，只有"场景 × 目标（吞吐 or 延迟 or 正确性）"的最优。
4. **Hikari 连接池**：爆池三联征（active=max / waiting 堆积 / 超时异常）；池大小 ≈ `核数 × 2 + 有效磁盘数` 起点，排队是有用信号（背压），无限调大只会把压力转嫁给 DB。
5. **Tomcat accept-count**：OS backlog 的吸收突发建连作用（治拒连），与工作线程数（治吞吐）是两个独立旋钮。
6. **Kafka 消费并发 = min(分区数, 消费者线程数)**：3 分区 1 线程时 2 分区空闲，concurrency=3 对齐；多余线程空闲无害。
7. **Docker Desktop(WSL2) 网络税**：10007（2 次 Redis 往返）比 40002（零 Redis）慢 2.3 倍——中间件容器化的性能口径要在报告里声明，跨环境数字不可比。
8. **配置双轨暗坑**：`spring.datasource.hikari.*` 在自定义 DataSource 接管后静默失效——调参前先确认"配置真的连到了 Bean"。

**面试必问题**
- Q：你压测的 12000+ QPS 是怎么测的？同机压测数字可信吗？
  要点：场景=缓存命中（L1 Caffeine 为主）、1000 并发 60s 时长档、断言业务码、错误率 0.007%；同机口径如实声明（客户端争 CPU，数字偏保守），分场景报数（拒绝 5333 / 令牌 1630 / 成功路径另一口径），绝不拿单场景数字当全站容量。
- Q：调优过程中最反直觉的发现？
  要点：threads.max 200→400 让缓存命中 +74% 但让未登录拒绝 -64%（P50/P90 双峰=调度饥饿）——CPU 饱和时线程翻倍是负优化；最终按主口径回退，反例数据保留在报告里。**有反例的调优才可信**。
- Q：为什么池调到 30 不是 100？
  要点：爆池时 waiting=122 是排队信号不是"池不够大"的信号；申请链路每次 1 条连接几 ms 周转，30 并发连接的周转能力 ≈ 数千 QPS 查询；调 100 只会把排队从应用侧转移到 MySQL 侧（max_connections=151，测试环境先爆给你看——回归阶段实证）。
- Q：10007 场景为什么只有 1630？
  要点：每请求 2 次 Redis 往返（登录态 HGET+EXPIRE、令牌消费 Lua GET+DEL），WSL2 NAT 放大每次往返；对照 40002（同路径零 Redis）5333——差异即 Redis 成本，是"下一步优化点"的证据（合并命令/管线化/本地会话缓存）。
- 追问：为什么"售罄 10004"没测？——令牌一次性消费（Lua GET+DEL）先于库存判断，每个 10004 请求都需要新鲜令牌而申请接口限流 3 次/分/用户，无法大批量稳定复现；这是压测设计与防刷机制的碰撞，如实记录（体现压测场景设计的边界思考）。

## 8. 下一步

M7-B 交付与面试弹药：部署文档 + 架构图 + README + 30 道自测题 + 简历 bullets 打磨（含把本主题实测数字回填简历）。
