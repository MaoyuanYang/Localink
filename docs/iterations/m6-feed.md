# 主题任务卡：M6-C Feed 流（推模式收件箱 + 滚动分页 + 推挽结合）

| 字段 | 内容 |
|---|---|
| 主题编号 | M6-C |
| 分支 | `feature/m6-feed`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-27） |

---

## 1. 目标

关注流三件套（原 M6.6/M6.8，F-COM-03）：推模式（发帖推送到粉丝收件箱）、score 游标
滚动分页（关注流专用分页契约）、推挽结合（大 V 拉、普通用户推）。收件箱是 Redis 常驻
ZSet（database.md 取舍 #8：不建表，拉模式兜底）；M6-B 埋的伏笔（"不建粉丝 Set 走
lk_follow 反查""USER_FOLLOWEE 是关注流数据源"）在本期兑现。业务边界（PRD）：Feed 仅
时间序，不做个性化推荐。

## 2. 主题内子任务

1. 推模式：发帖 → 反查粉丝（lk_follow idx_follow_user_id 分页遍历）→ 逐粉丝 ZADD 收件箱
2. 大 V 判定：作者 fans ≥ 阈值（默认 1000，可配）不推送——写扩散上界被阈值封顶
3. 收件箱截断：ZADD 后超上限（默认 1024，可配）popMin 至上限
4. 滚动分页：`GET /api/feed?lastScore=&size=`，score 位账 `毫秒<<12 | postId 低 12 位`，
   游标 exclusive（实现为取更老一侧 `(-inf, lastScore-1]`，§6 排障已自纠）翻页不重不漏；契约 `{records, nextCursor}`（ScrollVO）
5. 读端归并（推挽结合）：收件箱页 ∪ 我关注的大 V 帖页（DB 拉取+同公式换算 score），
   按 score 归并去重取前 size 条 → listOrdered 回填 → 按当前关注集合过滤（取关即时生效）
6. 契约与文档：web-frontend.md §5 补滚动分页约定行；database.md §7 Feed 行旧编号校正

## 3. 设计取舍

- **score 位账 = 毫秒<<12 | postId 低 12 位（41+12=53）**：时间位=发帖时刻应用毫秒，序号位=
  雪花 id 低 12 位（恰为雪花序列位，同毫秒内唯一 → score 全局唯一）。同分陷阱的解法：
  `(lastScore)` exclusive 游标 + 唯一 score——同毫秒/同秒多帖不重不漏。拉路径（大 V 帖）
  时间源=DB create_time（秒级精度），同秒内低 12 位跨毫秒可碰撞（1/4096）——演进声明：
  严格防漏需 (score, postId) 双键游标
- **推模式同步推、无事务包裹**：create 是单条 insert（autocommit），落库即事实，推送随后
  直接执行（M6-B 的 afterCommit 针对多语句事务，此处无回滚窗口）。推送 RT 随粉丝数线性
  涨——上界被大 V 阈值封顶（<1000 次命令），演示可接受；演进声明：真实粉丝量走 Kafka
  异步推 + pipeline 批量
- **大 V 不推 = 写扩散上界闭环**：作者 fans ≥ 阈值跳过推送，粉丝读时从 DB 拉。判定用
  User.fans 冗余计数（非实时精确，口径同 M6-B——大 V 判定容忍百级误差无业务影响）
- **读端归并而非读端全拉**：普通作者的帖已推（读收件箱零 DB），大 V 帖读时拉（DB 按
  create_time 倒序 limit）——两路按 score 归并去重。去重防御：关注切换（先推后变大 V）
  可能使同帖两路都出现，按 postId 去重
- **取关即时生效靠读端过滤**：收件箱是推送时刻的快照，取关不逐收件箱清理（写扩散清理
  代价 > 收益），读端按当前关注集合过滤——收件箱"多存"无害，时间线"不出现"为准
- **audit 过滤/删帖不清收件箱**：nextCursor 取归并结果（回填过滤之前）最后一条 score——
  被过滤帖不阻塞翻页；收件箱派生视图可由关注关系+帖子重建（"DB 事实源+派生视图+可
  重建"第四次落地）
- **收件箱截断用 popMin 循环**：不扩 starter 接口（ZREMRANGEBYRANK 留给真实量级）——
  单帖至多超额 1 条，循环至多 1 次；截断上限 1024=粉丝收件箱内存上界（ZSet 单成员
  约 100B 量级，1024 条约 100KB/人）
- **不推给自己**：关注流语义=我关注的人的内容（微信朋友圈式"含自己"是另一语义）；
  个人主页（我的帖子列表）接口留 W3 演进声明
- **大 V 帖 DB 拉取深翻页成本**：`create_time <= lastMillis` 倒序 limit size——大 V 帖稀疏
  时深翻页扫描量大；演进声明：时间分片下界（如仅最近 90 天）或大 V 帖也建轻量索引视图

## 4. 接口 / 表 / Redis Key / 配置

**接口**（新建 FeedController）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/feed?lastScore=&size=10` | 关注流（登录必须）；ScrollVO{records, nextCursor}，null 到底 |

**表**：无 DDL 变更。复用 lk_post（idx_create_time 支撑拉路径）、lk_follow（idx_follow_user_id 反查粉丝）、lk_user（fans 判定大 V）。

**Redis Key**（KeyManage 登记）

| Key | 模板 | 结构 | TTL | 恢复策略 |
|---|---|---|---|---|
| USER_FEED | `user:feed:%s`（userId） | ZSet（member=postId，score=毫秒<<12\|postId低12位） | 常驻 | 关注关系+帖子重算（截断丢失=老帖不可见，接受） |

**配置**（application.yml `localink.feed`，FeedProperties）

| 配置 | 默认 | 说明 |
|---|---|---|
| `big-v-fans-threshold` | 1000 | 作者 fans ≥ 此值判定大 V：不推送、读时拉 |
| `inbox-max-size` | 1024 | 单粉丝收件箱上限，ZADD 后 popMin 截断 |

## 5. 产出物

| 文件 | 说明 |
|---|---|
| api-model `ScrollVO` | 滚动分页契约 `{records, nextCursor}`（与 PageVO 并存，W3 前端直接用） |
| cache-starter `RedisZSetOps` | 新增 `reverseRangeByScoreWithScore`（游标翻页需要 score 回传，offset/count 版拿不到）+ 默认实现 + starter 测试 1 例 |
| `constant/KeyManage` | 登记 USER_FEED（`user:feed:%s`） |
| `config/FeedProperties` | big-v-fans-threshold=1000 / inbox-max-size=1024（@EnableConfigurationProperties 注册） |
| `event/PostCreatedEvent` | 发帖事实事件（进程内解耦：Post 不认识 Feed） |
| PostServiceImpl | create() 落库后 publishEvent——事实与派生投影分离 |
| `service/FeedService` + Impl | 推送（大 V 不推+popMin 截断+失败不阻塞发帖）+ 读端归并（收件箱页 ∪ 大 V DB 拉取页，按 score 归并去重+关注集合过滤） |
| `controller/FeedController` | GET /api/feed（登录必须） |
| application.yml | `localink.feed` 配置段 |
| web-frontend.md §5 | 滚动分页契约行首次落地 |
| 测试 ×7 | FeedIntegrationTest 6 例 + FeedInboxTrimIntegrationTest 1 例（properties 覆盖+@DirtiesContext） |

## 6. 验证记录（2026-09-27 本机实测）

主题 7/7（Feed 6 + 截断 1）；全 reactor **253/253**（245 基线 + Feed 7 + starter 1）**两遍
稳定**，BUILD SUCCESS；KeyManage 契约锁通过。

**排障实录**：
①**翻页方向 bug（写测试时推演发现）**：初版 `reverseRangeByScore(lastScore+1, +inf)` 取的是
"比游标更新"的一侧——已服务内容重取、更老内容漏取。时间线翻页取**更老**：正确区间
`(-inf, lastScore-1)`（整数 score，-1 即排除已服务本条）；大 V 拉路径 score 过滤同步改
"< 游标"。教训：游标方向 = 数据流向，写测试用例推演翻页序列能先于运行暴露方向错
②**MySQL "Too many connections"**：`@SpringBootTest(properties)` 覆盖产生独立
ApplicationContext（各占 ds_0/ds_1 两个 Hikari 池），全量运行时多上下文并存累积挤爆本机
MySQL max_connections=151，殃及后续 shop 测试类建上下文。修复：截断测试类加
`@DirtiesContext(AFTER_CLASS)` 跑完即关——两遍全量验证稳定
③starter 镜像测试初版期望值写错（seed 里 b 的 score=3 非 2），按 seedZset 实际数据修正
④**DB 拉路径同秒并列**：DB 只按 (create_time,id) 排序，同秒并列时与 score 序（id 低 12 位）
不一致——拉取缓冲扩为 size*2 消解页边界截断，归并侧全局按 score 排序淘汰多取行

## 7. 学习清单

**核心知识点**
1. **推/拉模式的读写复杂度账**：推=写扩散（发帖 O(fans)，读 O(1) 收件箱）；拉=写 O(1)、
   读扩散（O(关注数) 次 DB 查）。推挽结合=普通作者推、大 V 拉——**大 V 阈值同时封顶了
   写扩散上界**（单帖至多推 < 阈值 个收件箱），这是两个参数变成一个的原因
2. **score 位账 41+12=53**：同分陷阱（同毫秒/同秒多帖 score 相同 → exclusive 游标会漏）
   的解法=时间位+序号位拼唯一 score；序号位用雪花 id 低 12 位（恰为雪花序列位，同毫秒
   内唯一）。53 位是 double 精确整数的安全上界
3. **读端归并**：两路各自有序（收件箱按 score 倒序、DB 按 create_time 倒序+换算 score），
   全局归并去重——去重防御"先推后变大 V"的同帖两路出现；时间线语义在归并层统一
4. **事实与派生投影分离**：PostCreatedEvent 进程内事件解耦（Post 不知道 Feed 存在）；
   推送失败 catch 告警不阻塞发帖——投影可由"关注关系+帖子"重算，与点赞榜/ES/对账同一心智
5. **收件箱截断**：popMin 循环至上限（不扩 ZREMRANGEBYRANK 接口，超额至多 1 条）；
   截断丢失=老帖淡出时间线，接受
6. **取关/删帖/驳回的读端过滤**：收件箱是推送时刻快照，不逐粉丝回写清理（写扩散清理
   代价 > 收益）；nextCursor 取归并结果（回填过滤前）——被过滤帖不阻塞翻页
7. **测试工程**：properties 覆盖=独立 ApplicationContext（连接池翻倍），@DirtiesContext
   及时回收——本机中间件资源是测试的隐式共享状态

**面试必问题**
1. "Feed 流怎么设计的？"——推模式收件箱 + score 游标滚动分页 + 大 V 拉模式读端归并三件套；
   追问"为什么不全推/不全拉"→读写复杂度账+大 V 阈值封顶写扩散
2. "滚动分页为什么不用 offset？游标怎么设计？"——offset 插入敏感（新帖挤入导致重复/漏）；
   exclusive 游标 + 位账唯一 score 防同分漏帖；追问"和 SQL keyset/ES search_after 什么
   关系"→同一思想（排序键+游标）在不同存储的落地
3. "大 V 怎么判定？阈值为多少？"——fans 冗余计数 ≥ 可配阈值（默认 1000）；追问"计数漂移
   会怎样"→判定容忍百级误差，边界抖动只是该帖推/拉路径切换，读端归并兜底
4. "取关后收件箱里的旧帖怎么办？"——读端按当前关注集合过滤，不清理收件箱；追问"为什么
   不清理"→逐粉丝回写是第二次写扩散
5. "发帖推送会不会拖慢发帖/推送失败怎么办？"——同步推上界=阈值内粉丝量（演示可接受），
   真实量级 Kafka 异步+pipeline；失败 catch 告警，投影可重算（事件解耦的价值）

## 8. 下一步

**M6-D 搜索**：ES 环境与 elasticsearch-java client 接入 + 发帖 → Kafka → 消费写 ES
（复用 MQ 框架，同帖分区 key 防 FIFO 乱序）+ 搜索接口（全文检索/高亮/商户聚合筛选，
search_after 深分页）。

> **M8 回溯注记（2026-09-29，docs/VERIFICATION.md 审计 + m8-audit-fixes 修复）**：推/拉两路 score 口径原不一致（推=应用毫秒，拉=DB 秒对齐），