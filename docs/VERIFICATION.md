# Localink 项目全量验证报告（project-verify）

| 项 | 值 |
|---|---|
| 验证日期 | 2026-09-29 |
| 验证基线 | main @ `799779d`（PR #78 合并后，工作区干净，远端==本地） |
| 验证范围 | 后端主线 M0~M7 全部 23 个已完成主题（roadmap `[x]` 行）；Web 前端线 W0~W4 未启动，不在范围 |
| 验证方式 | 文档承诺提取（README/docs 全部文档 + 53 张任务卡）→ 声明命令实跑 → 23 主题逐条核验 → 运行时黑盒演练 → 并发正确性抽测 → JMeter 三场景复跑 → 5 路深度代码审查（只读）→ 文档↔实态比对 |
| 结论 | **后端主线交付真实可信**：287/287 测试干净全绿、23 主题全部 Verified（其中 2 主题带 Broken 级实现瑕疵）、性能头条数字复现成功；同时发现 **7 项 P1 级代码缺陷**（集中在故障路径与兜底层缝隙）、2 项运行时坐实的文档失实、15+ 项文档漂移。全部问题仅记录，未做任何修复（审计独立性） |

> 本报告为独立审计产物：验证过程新增文件仅 `scripts/verify/`（4 个验证脚本）与本报告；业务代码零改动、git 零提交。验证产生的测试数据已按物理表口径清扫并复查计数归零（含 ES）。
>
> **修复状态附录（2026-09-29，feature/m8-audit-fixes）**：§5/§6 全部发现已按用户指示修复——7 项 P1、19 项 P2、可加固 P3 与文档漂移 D-1~D-19；逐项映射与取舍见 `docs/iterations/m8-audit-fixes.md`。**勘误**：D-13 经修复期代码复核修正定性——20001（服务层，同手机号 120s）与 40008（切面层，同 IP 60s）分层并存，非文档失实，m1-3 已补分层口径注记。新增 9 项回归测试锁定修复行为。

---

## 1. 总览：23 个主题核验矩阵

标注口径：**Verified** = 存在可执行证据且通过；**Broken** = 证据显示承诺与实态不符（按发现编号引用 §5/§6）；**Unverified** = 本轮无法产生可执行证据。

| 里程碑 | 主题（roadmap 行） | 判定 | 证据摘要 |
|---|---|---|---|
| M0 | 工程奠基 | Verified | 12 模块编译构建 SUCCESS（1:41）；PRD/架构/部署/中间件文档齐备且命令可执行；PR #1~#78 已合并（gh 只读核对） |
| M1 | 基础闭环 | Verified | 短信→登录→/me→鉴权黑盒全通（§4.1）；表结构与 sql/*.sql 一致；错码 20002/烧码 GETDEL 语义运行时实证 |
| M2 | Redis 工具框架与 Key 治理 | Verified* | KeyManage 25 key 契约测试绿；*但存在 3 处绕过门面的裸用（发现 C-4） |
| M2 | 缓存三防 | Verified | 空值缓存/雪崩抖动/击穿逻辑过期均有测试+黑盒（穿透空值、改名失效读新值实证）；*逻辑过期重建存在竞争窗口（发现 A-4） |
| M2 | 多级读防线 | Verified* | L1 Caffeine→布隆→Redis 逻辑过期四级链路运行时通；*「Redis 宕机 L1 兜底」对带 token 请求不成立（发现 D-5） |
| M3 | 纯 DB 下单与超卖实录 | Verified | 历史结论由任务卡+压测脚本佐证；演进代码保留 |
| M3 | 分布式锁与一人一单 | Verified* | 一人一单运行时实证（10004/10005 精确复现、100 人抢 50 库存 0 重复）；*@ServiceLock 已从热路径退役，roadmap 口径未更新（发现 D-1） |
| M3 | Redis+Lua+Kafka 异步秒杀 | Verified | 令牌→Lua 扣减→Kafka 异步建单全链路黑盒实证，订单精确落双库物理表，Redis/DB 库存逐位对齐（§4.3） |
| M3 | 一致性闭环 | Verified* | 幂等/退避/回滚/对账测试 187 项全绿+启动对账日志实证；*建单幂等闸门晚于 DB 扣减存在幻影扣减窗口（发现 A-1，两个审查路径独立确认） |
| M3 | 流量防线 | Verified | 限流运行时实证（发码 40008、令牌 10007 一次性、压测三场景 0 超发）；50 并发恰放行 10 由测试锁定 |
| M4 | 全局 ID | Verified | id-starter 9 测试绿（含时钟回拨分档）；订单 ID 运行时为雪花形态 |
| M4 | 双库分片 | Verified | **运行时精确验证**：uid 奇偶→ds_0/ds_1、voucherId 奇偶→表_0/_1，149+50+1 笔订单路由逐位正确，lk_order_route 同步写入 |
| M5 | M5-A 对账体系 | Verified* | 启动对账日志+集成测试绿；*行龄告警结构性不可达、挂单被翻牌一致（发现 A-3、D-8） |
| M5 | M5-B 延迟队列与超时关单 | Verified* | 集成测试绿；*关单三步非原子，崩溃窗口 DB 库存泄漏无恢复路径（发现 A-2）；15 分钟全时长关单未做运行时观察（Unverified 子项） |
| M5 | M5-C 通知与运营统计 | Verified* | 集成测试绿；*预通知 SETNX 无 TTL 已运行时坐实堆积（发现 A-5）；订阅回流发券可触发幻影扣减（并入 A-1） |
| M6 | M6-A 内容基础 | Verified | 发帖/评论/楼中楼黑盒全通；上传链路安全审查通过（UUID 落盘+白名单+5MB） |
| M6 | M6-B 互动关系 | Verified* | 点赞/取关计数防负、关注/共同关注黑盒通；*「点赞榜可重算」未兑现（发现 D-6）；userId 裸数字出参违背 id 字符串化承诺（发现 D-14，运行时坐实） |
| M6 | M6-C Feed 流 | Verified* | 先关注后发帖→收件箱命中实证、exclusive 游标测试绿；*推/拉两路 score 口径不同存在跨页重复窗口（发现 B-6）；大 V 跨阈值场景未运行时构造 |
| M6 | M6-D 搜索 | Verified | Kafka→ES 异步同步 ≤30s 实证、ik 7/5 词分词实测与文档一致、**XSS 转义运行时实证**（搜索响应无裸 `<script>`）；*rebuildAll 无生产触发点（发现 D-7） |
| M6 | M6-E 热点统计 | Verified | 热榜接口通、公式与配置逐项一致（λ/权重/候选集由代码审查确认）、半衰期用例容差 0.05 测试绿 |
| M6 | M6-F 风控与特色 | Verified* | DFA 两级审核运行时全通（显性 30001 同步拒、隐性异步驳回 audit=2+详情 40004）、签到、GEO 全通；*GEO 无范围校验可致重启失败（发现 A-6） |
| M7 | M7-A 全链路压测与调优 | Verified* | 4/5 场景达标或反超（§4.5，含 12310 QPS 头条复现 12683）；*flow400「100% 零拒连」本轮未复现（环境负载差异，如实记录） |
| M7 | M7-B 交付与面试弹药 | Verified | 架构 v2.0/部署手册/README 数字与代码交叉核对基本一致；少量口径漂移见 §6 |

**汇总：23 Verified（含 11 个带瑕疵星标）、0 个整体 Broken、0 个 Unverified（主题级）。**

---

## 2. 声明的验证命令（declared verification）

| 承诺命令（出处） | 结果 |
|---|---|
| `docker compose up -d mysql redis kafka`（README/deploy §2） | ✅ 三容器 healthy（本轮环境已在线，直接复用） |
| `docker compose --profile es up -d elasticsearch` | ✅ ES 8.15.5 + analysis-ik 8.15.5 插件在位 |
| `.\mvnw.cmd clean package "-DskipTests"`（README 快速开始） | ✅ BUILD SUCCESS，1:41 |
| `./mvnw clean test`（AGENTS.md §10 全量回归口径） | ✅ **287/287 全绿，BUILD SUCCESS**（clean 后干净运行；分模块 cache 52 / lock 9 / idempotent 6 / ratelimit 16 / mq 4 / delay 3 / id 9 / search 1 / server 187，与 README badge 逐位一致）；**压测清扫后二次回归复跑：再次 287/287 BUILD SUCCESS**（验证清扫彻底性，完整复刻 M7-A §6.4 协议） |
| `java -jar ...`（deploy §4，`-Xms512m -Xmx2g`） | ✅ 约 20 秒探活成功（文档口径 25~40s，相符） |
| `curl /ping` → pong；`curl /api/shop/1` → code 0 | ✅ |
| deploy §3 SQL 双库初始化验证三连 | ✅ 表数与期望一致；*lk_shop 种子数 43≠10 属本机历史数据残留（非脚本问题；首装口径以 sql 内 INSERT 计为准） |
| deploy §6 启动自检四条日志 | ✅ **四条全部命中**：库存回灌/对账完成（差异补偿=0笔）/布隆 count=45/GEO total=45 indexed=13 |
| deploy §6 消费组三组 | ✅ localink-server-seckill-order / -post-audit / -post-search 齐全 |
| scripts/jmeter 三场景复现命令（jmeter README） | ✅ 全部可执行，结果见 §4.5 |
| ik 分词验证（middleware-setup §4） | ✅ ik_max_word 7 词 / ik_smart 5 词，与 m6-search 实测记录完全一致 |
| GitHub 证据 | ✅ 远端 main==本地 HEAD（799779d）；PR #71~#78 按 roadmap 顺序全部 merged |

> **过程记录（诚实披露）**：本轮首次全量测试曾 BUILD FAILURE（97 run / 55 errors）——事后确认是**验证操作自身的干扰**：测试运行期间并发启动了 `package` 构建，classpath 被重写导致 `NoClassDefFound`/`@SpringBootConfiguration` 类错误；且未 `clean` 时 surefire 报告与上一轮残留混读。作废该轮后，在无并发干扰环境下 `clean test` 得到上述 287/287 权威结果。该事件同时暴露一个环境事实：**这套集成测试对并发构建/多 JVM 敏感**（deploy.md FAQ「Too many connections」条目与此互证），单线程串行执行是其隐含前提。

---

## 3. 运行时黑盒演练（关键用户流）

环境：本机 22 逻辑核，应用 -Xmx2g + 全套 Docker 中间件；脚本 `scripts/verify/api-blackbox.sh`（两轮）+ `api-blackbox-part2.sh` + 内联补验。首轮 9/42 → 修正脚本问题（用例时序、终端 GBK 编码、断言写法）后 **29+8+补验全绿**。

### 3.1 账户与鉴权（M1）
发码→Redis 明文码→登录（32 位 token）→/me ✓；无 token 40002 ✓；**验证码一次性消费语义实证**：错码尝试即烧码（GETDEL），烧码后真码不可再用（20003）——防爆破设计兑现。

### 3.2 商户与缓存（M2/M6-F）
详情冷/热双读 ✓；不存在商户 40004 ✓；GEO nearby ✓；**改名后立读新值**（旁路缓存先 DB 后删缓存 + L1 广播失效）✓。

### 3.3 秒杀两步流（M3/M4）——运行时全链路
令牌发放（UUID）→ 令牌下单（异步受理，orderId 预生成）→ Kafka 异步建单落双库物理表 ✓ → 重复下单 **10005** ✓ → 伪令牌 **10007** ✓ → DB/Redis 库存逐位对齐（5→4）✓ → 三件套 key（stock/order/flow 带 hash-tag）形态与文档一致 ✓。

### 3.4 社区链路（M6）
发帖 ✓；显性敏感词同步拒 **30001** ✓（UTF-8 实测）；隐性风险词先放行→异步复审驳回（audit_status=2、详情转 40004）✓；评论+楼中楼 ✓；点赞/取消（计数防负）✓；关注/共同关注交集内容正确 ✓；**先关注后发帖→Feed 收件箱命中** ✓；ES 搜索 ≤30s 命中 + **高亮 XSS 转义实证**（响应无裸 `<script>`）✓；热榜 ✓；BitMap 签到+状态 ✓；非作者删帖 **40003** ✓；作者删帖 ✓。

### 3.5 发现的运行时问题（坐实为 finding）
- 重发验证码返回 **40008**（通用限流码），而 m1-3 任务卡承诺 20001——行为正确、错误码口径漂移（D-13）。
- 共同关注返回的 `userId` 为**19 位裸数字**（`UserBriefVO.userId` 未字符串化），违背 m1-5/architecture「id 出参转字符串防精度丢失」承诺（D-14）。
- 游客 GET `/api/notice`、`/api/seckill-voucher/{id}/subscribe` NPE→500（应 40002），代码审查定位 `UserHolder.get().getId()` 无判空（B-3）。
- `lk:seckill:notice:sent:*` 键 **148 个常驻不过期**——KeyManage 登记 TTL 1 天但 SETNX 未传（A-5 运行时坐实）。

---

## 4. 并发正确性与压测复跑

### 4.1 并发正确性抽测（100 用户抢 stock=50，seckill-flow.jmx）

| 检查项 | 期望 | 实测 | 判定 |
|---|---|---|---|
| 订单总数（4 物理表合计） | 恰好 50 | **50** | ✅ 零超卖 |
| 每用户订单数 | ≤1 | 无任何 >1 | ✅ 一人一单 |
| DB 库存 | 0 | **0** | ✅ |
| Redis 库存 | 0 | **0**（与 DB 对齐） | ✅ |
| Redis 已购集合 | 50 | **50** | ✅ |
| lk_order_route 行数 | 50 | **50** | ✅ |
| 对账流水条数 | 50 | **50** | ✅ |
| 分片路由 | uid 奇偶定库 / vid 奇偶定表 | 28 偶用户→ds_0.表_1、22 奇用户→ds_1.表_1（vid 为奇→表_1） | ✅ 逐位正确 |

### 4.2 JMeter 三场景复跑（对照 M7-A 定稿，同机口径、60s 时长档）

| 场景 | M7-A 定稿 | 本轮复跑 | 判定 |
|---|---|---|---|
| 缓存命中 @1000 线程 | **12310 QPS** / P99 172ms | **12683 QPS / P99 155ms / 错误率 0** | ✅ **头条数字复现成功** |
| 缓存命中 @100 线程 | 10038 / P99 57 | 12614 / P99 40 | ✅ 反超 +26% |
| 未登录拒绝 @300 | 5333 / P99 461 | **8507 / P99 98** | ✅ 反超 +60% |
| 无效令牌拒绝 @100 | 1630 / P99 101 | 1114 / P99 157 | ⚠️ 同千级档，未达文档值（-32%；本轮会话含后台审查负载，环境口径已在报告中声明） |
| 成功路径 flow-400 | **800/800=100%，零拒连** | 149/400 成单 + 90 拒连；**但零超卖/零重复/账实零错** | ❌ 未复现（见 4.3） |

**结论**：性能档案主体可信——缓存命中与拒绝路径数字可复现或反超，「单机 QPS 2000+、P99<500ms」的简历口径成立；flow 场景的「100% 零拒连」未能在本轮环境复现。

### 4.3 flow400 未复现的归因（如实记录）
- 拒绝码侧无异常：flow 期间应用日志无 10004/10005/10007 峰值（全部计数可归属其他场景），失败集中在 **90 个 HttpHostConnectException（建连拒绝）**。
- 正确性零错：149 笔成单全部落库、零重复、DB=Redis 库存——**失败是「没能下单」而非「下错单」**。
- 环境差异：本轮为审计会话（ES/中间件/审查代理等常驻负载）+ JMeter 未加大堆（400 线程档 README 未要求）；M7-A 实测时为专注压测的机器状态。同机压测数字本就受并发负载影响（M7-A 自己声明「数字偏保守」），本记录与其口径一致。

### 4.4 收尾清扫（M7-A 排障实录 11 协议）
按物理表口径清扫验证/压测数据后复查计数：**用户 0 / 券 0 / 订单 0 / 帖子 0 / Redis seckill key 0 / ES `post` 索引 verify 帖 0**——清扫彻底。清扫后全量回归见 §2（287/287）。

---

## 5. 深度代码审查发现（5 路并行只读审查，P0~P3 分级）

> 交叉确认标记 † = 两个独立审查路径发现同一问题。以下仅列 P1 全部与 P2 摘要；P3 明细见各审查输出（约 40 项），本报告归档在案。

### A. P1（7 项——正确性/一致性缺陷，均有明确触发路径）

| # | 领域 | 问题 | 位置 | 触发与影响 |
|---|---|---|---|---|
| A-1† | 秒杀消费 | **DuplicateKeyException 被吞后事务照常提交 → 幻影扣减 DB 库存**：`deductStock` 在 insert 之前执行，撞唯一索引后 catch+return，先扣的 DB 库存不回补且对账不可见 | `VoucherOrderServiceImpl.java:117-136` | ①同 orderId 重投+幂等标记丢失窗口；②**订阅回流自动发券对已持券用户每次都会触发**（不需标记丢失）——DB 永久少卖 1/次，Redis↔DB 漂移，对账只按 traceId 对存在性、永远发现不了 |
| A-2† | 超时关单 | **关单三步非原子且中段失败不可重入**：`closeIfCreated`→`restoreStock`→Redis 回滚无事务；首步成功后崩溃，重投时 `closed==0` 早退，DB 库存永不再回补；对账裁决只补 Redis 侧 | `VoucherOrderServiceImpl.java:230-246`、`ReconciliationJob.java:133-147` | 崩溃窗口内 DB 库存永久泄漏（少卖），启动回灌将误差固化扩散 |
| A-3 | 对账 | **延迟任务丢失 → status=1 挂单被对账翻牌为「一致」**：RBlockingQueue take 即离队、崩溃即丢、耗尽仅 log.error；对账对 status=1 走 settleConsistentTrace；全项目无超时扫单任务 | `ReconciliationJob.java:133-147`、`DelayQueueConsumer.java:16-19,102-105` | 挂单用户资格占死、库存双端不归还、被标记 CONSISTENT、无告警——「关单丢一条由对账兜底」的声明不成立 |
| A-4 | 缓存一致 | **逻辑过期重建与 update/delete 的旧值回填竞争（无 fencing）**：两条重建路径均为「查 DB→无条件 set」，可把 delete 后的旧 entry 写回，驻留一个逻辑 TTL 周期（30~40 分钟） | `ShopServiceImpl.java:100-134,154-164` | 并发更新与重建交错时脏读窗口长达半小时级 |
| A-5 | 治理 | **预通知 SETNX 无 TTL 且绕过 RedisCache 门面**：`setIfAbsent(sentKey,"1")` 未传 TTL（KeyManage 登记 1 天），且是 M2.2 治理闭环声明后新增的裸用 | `SeckillNoticeConsumer.java:77-78` | 每券永久残留一个 key（**运行时实测 148 个堆积**）；「枚举即文档」契约失守 |
| A-6 | 社区 | **GEO 经纬度无范围校验 + create/update 无事务 + 启动灌入不兜错**：越界坐标可落库，GEOADD 报错后脏行已提交，下次重启 `ShopGeoInitializer` 无 try-catch 直接启动失败 | `ShopServiceImpl.java:184-208`、`ShopDTO.java`、`ShopGeoInitializer.java:31-44` | **单次 API 调用即可造成持久性启动 DoS**，只能手工删库行恢复 |
| A-7 | 授权 | **管理端点零权限区分**：鉴权只有认证两档（GET 放行/非 GET 或 /api/user/** 需登录），无角色概念 | `LoginInterceptor.java:22-24` + Shop/ShopType/Voucher/SeckillVoucher 四组 Controller | 任意注册用户可增删改商户/券/秒杀活动（含直接预热 Redis 库存）。dev 作品可接受，真实部署属越权写 |

### B. P2 摘要（19 项，去重后）

- **B-1** 活动中 `update()` 覆盖式重灌库存：可抬回已售量造成相对超卖、丢失在途扣减（`SeckillVoucherServiceImpl.java:104-111`）。
- **B-2** 建单消费幂等闸门（@RepeatExecuteLimit）晚于 DB 扣减副作用本身（与 A-1 同根因的实现位置问题）。
- **B-3†** 游客 GET `/api/notice`、`/api/seckill-voucher/{id}/subscribe` NPE→500（`NoticeController.java:28`、`SubscribeController.java:44`）。
- **B-4** 无登出端点 + 滑动续期 = token 活跃期永不过期，泄露后无服务端失效手段。
- **B-5** 布隆 add 在 insert 之后：Redis 抖动半完成态下新建商户被布隆永久误拦（重启才恢复）。
- **B-6** Feed 推/拉两路 score 口径不同（毫秒 vs 秒对齐）：作者跨大 V 阈值后翻页出现跨页重复帖。
- **B-7** post-audit/post-search 消费者无专属错误处理器：默认重试 9 次后**静默丢弃**，审核消息丢失则风险帖永久可见（无兜底）。
- **B-8** 点赞榜/关注集「可重算」未兑现：Redis 丢失后 top()/feed()/commonFollows() 永久空转，无 DB 回退。
- **B-9** `rebuildAll` 无生产触发点（仅测试调用），ES 漂移生产不可修复。
- **B-10** 审核驳回收回不清理点赞榜/UV：僵尸 member 永久占据 ZSet，榜单长期不足额。
- **B-11** 失败表「删旧走新」重置 create_time → **行龄 24h 告警结构性不可达**。
- **B-12** 对账主循环无逐笔异常隔离：单条毒流水令对账整体永久停摆（retryRollbackFailures 也不再执行）。
- **B-13** workId 分配失败静默回退 0（注释声称「启动失败优于静默不可用」），多实例 Redis 故障期同启会跨实例撞号。
- **B-14** TokenRefreshInterceptor 前置查 Redis：**带 token 的请求在 Redis 宕机时 500**，「L1 兜底」仅对匿名请求成立。
- **B-15** afterCommit 回调无异常包裹/重试/补偿：提交后 Redis/Kafka 抖动即丢增量且接口 500。
- **B-16** putAll/set+expire 两步非原子（会话/库存/令牌 key 家族）。
- **B-17** 明文密码三处入库、无 profile 拆分（dev 可接受，AGENTS.md 条款未落地）。
- **B-18** 短信验证码+手机号明文打日志（`SmsServiceImpl.java:35`，全库唯一敏感信息入日志点）。
- **B-19** 分页/查询参数无上限（post/page、search size）、GEO 坐标无范围、券金额关系无校验。

### C. P3 汇总（约 40 项，择要）
事务内非 DB IO（已文档化）、线程池收尾缺 awaitTermination、L1 存可变引用、上传 delete 路径穿越（死代码）、DFA 跨字段拼接误杀、评论删除并发窗口、楼中楼无界查询、ES liked 快照失真、XFF 信任、限流时钟前跳、雪花分片 INLINE 负值 Groovy 语义、对账全分片广播查询、HLL entries 内存尖峰、CacheInvalidation 消费组随启停无限累积（**本机实测 400+ 遗留消费组**）、Knife4j 仅版本管理未引入（AGENTS.md 技术栈表漂移）、Hutool 零引用冗余等。

### D. 审查确认无问题的关键面
全局异常处理（不吞堆栈/不泄漏）、ThreadLocal 无泄漏、事务传播无自调用失效、MQ 手动 ack+退避+Recoverer 闭环（seckill 链路）、Lua 三脚本原子性与对称回滚、Redisson 锁无误删/watchdog 正确、@RateLimit fail-open 不二次执行业务、幂等三级顺序正确、雪花回拨分档、ES 检索无注入面/转义完整/分面保全集、上传主路径安全、越权面（资源归属校验）完备、雪花分片键无倾斜（反编译实证 MP 序列位随机）。

---

## 6. 文档↔实态比对发现（承诺 vs 现实）

| # | 文档声明 | 实态 | 严重度 |
|---|---|---|---|
| D-1 | roadmap M3 行「@ServiceLock AOP + 唯一索引兜底三层防线」表述为现行口径 | @ServiceLock 生产代码零使用（M3.6 已退役，interview-qa 已承认），现行=令牌→Lua→唯一索引→幂等标记 | P2（演进史与现状混写，面试口径风险） |
| D-2 | roadmap M5-B「关单闭环」/m5-delay-close「对账兜底」 | 见 A-2/A-3：兜底层自身存在覆盖不到的缝隙 | P1 关联 |
| D-3 | m5-reconcile「行龄告警 24h=人工介入信号」 | 结构性不可达（B-11） | P2 |
| D-4 | m2-2「迁移后全仓无业务裸用」「绕过治理无法通过编译」 | 3 处裸用（A-5 的 SETNX 为实证后果） | P2 |
| D-5 | m2-10/README「Redis 宕机 L1 兜底热 key 200」 | 仅匿名请求成立（B-14） | P2 |
| D-6 | M6-B「点赞榜 afterCommit 增量**可重算**」/KeyManage「漂移可重算」 | 重算任务/端点不存在（B-8） | P2 |
| D-7 | M6-D「rebuildAll 全量重建兜底」 | 无生产触发点（B-9）；deploy.md §7 已如实声明「无 HTTP 管理端点」——两文档口径不一 | P2 |
| D-8 | KeyManage 多 key TTL 登记（NOTICE_SENT 1 天/Top买家 2 天/UV 30 天/通知箱 30 天） | 从未施加 expire，纯文档（A-5 运行时坐实） | P1 关联 |
| D-9 | m6-audit「驳回复用删帖链路」 | 只复用 ES DELETE 段，点赞榜/UV 不清（B-10） | P2 |
| D-10 | m2-3「业务缓存必须设 TTL，脏数据由 30 分钟 TTL 兜底」 | M2.7 起正缓存无物理 TTL（改逻辑过期），机制文档未回溯 | P3 |
| D-11 | m2-9 布隆启动窗口「miss 即重建本身能兜住」 | 自相矛盾：布隆在缓存之前，误拦直接 404 | P3 |
| D-12 | AGENTS.md §6「入参用 DTO+jakarta 校验」「环境差异走 profile」 | @RequestParam/@PathVariable 全零校验；单一 application.yml | P2 |
| D-13 | m1-3「重发验证码→20001」 | 实际 40008（@RateLimit 场景化接管后未回溯文档）——**运行时坐实** | P3 |
| D-14 | m1-5/architecture「id 为 Long 出参转字符串」 | `UserBriefVO.userId` 裸数字——**运行时坐实**（19 位精度丢失风险） | P2 |
| D-15 | PRD §5「回滚/限流/对账指标暴露到 /actuator」 | 全仓库无 actuator 依赖，承诺未兑现 | P2 |
| D-16 | deploy.md §3①「localink.sql=建库+11 非分片表+物理分片表+种子」 | 实际 localink.sql 建 12 表（含**逻辑**订单/流水表），物理表在 sharding.sql——①②内容归属描述串位；本机库无逻辑表属环境演化 | P3 |
| D-17 | interview-qa-30「从 52 张任务卡精选」；architecture「12 模块」vs M0 卡「13 模块」 | 实际 53 张；模块计数口径漂移 | P3 |
| D-18 | m6-feed §2「游标 exclusive（lastScore+1）」 | 实现方向是 (−inf, lastScore−1]（§6 排障已自纠但 §2 未改） | P3 |
| D-19 | architecture §4 V1 描述「500 请求超卖 9 单」与 qa #1「300 并发 500 请求」 | 两处口径不一致（数字本身有任务卡佐证） | P3 |

---

## 7. Unverified 清单（本轮无法产生可执行证据）

| 项 | 原因 |
|---|---|
| 双实例缓存失效广播真双实例验证 | 单实例环境；m3-9 已自我声明并以协议级论证+独立消费组用例替代 |
| 15 分钟延迟关单全时长生命周期 | 有 OrderCloseIntegrationTest 覆盖；运行时未等满 15 分钟（订单已按清扫协议删除） |
| C5 系列缓存实验数字（Com_select 差值/L1 削流 0 增量等） | 属一次性实验记录，本轮以测试类存在性+逻辑审查替代复测 |
| W0~W4 前端线 | 规划态，by design 未启动 |

---

## 8. 验证资产与数据处置

| 文件 | 说明 |
|---|---|
| `scripts/verify/api-blackbox.sh`、`api-blackbox-part2.sh` | 黑盒演练脚本（含两轮修正记录，可重复执行） |
| `scripts/verify/verify-cleanup.sh` | 收尾清扫（物理表口径+复查计数） |
| `scripts/verify/last-run.env` | 演练上下文（券/单/用户 ID） |
| `scripts/jmeter/results/*.jtl` | 本轮压测原始数据（gitignore 约定，不入库） |

数据处置：验证/压测数据已全部清扫并复查归零（DB 双库+Redis+ES）；中间件容器与既有数据未受影响。**业务代码零改动、零提交。**

---

## 9. 建议的后续工作（优先级序，均属 project-dev 范畴）

1. **修 A-1/A-2/A-3（订单一致性三缝）**：DuplicateKeyException 穿透或撞键回补库存；关单步骤收敛为事务或补恢复路径；对账增加「status=1 超龄」补关单裁决。三者共同构成「兜底层自身的缝隙」，是全部发现中唯一动摇 M5「一致性闭环」主题成色的部分。
2. **修 A-6（GEO 启动 DoS）**：DTO 加经纬度范围校验 + 启动灌入逐店 try-catch，成本极低收益极高。
3. **修 A-5/A-7**：SETNX 补 TTL 并回归门面；管理端点加最小权限闸门（或文档明示 dev 边界）。
4. **文档回溯批次**：D-1（锁退役口径）、D-13/D-14（两处运行时坐实的失实）、D-16（deploy §3 描述）、D-17（计数漂移）——面试场景下口径失实比代码缺陷更伤可信度。
5. P2 批次择机处理（B-7 审核消息兜底、B-11 行龄告警、B-13 workId 注释相反等）。
6. 环境卫生：清理 400+ 遗留 Kafka 消费组与历史 notice:sent 键；README/deploy 可补一句「测试须串行执行」的隐含前提。

---

*报告由 project-verify 审计流程生成：文档定义承诺 → 实态逐条对照 → 证据留档 → 只记录不修复。所有代码位置以 main @ 799779d 为准。*
