# 主题任务卡：M6-E 热点统计（HLL 浏览 UV + 加权×时间衰减热榜 + 定时快照）

| 字段 | 内容 |
|---|---|
| 主题编号 | M6-E |
| 分支 | `feature/m6-hot`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-28） |

---

## 1. 目标

热点三件套（原 M6.12/M6.14，F-COM-05 + F-ACC-05）：帖子浏览 UV 去重（HyperLogLog）、
热榜分数模型（点赞/评论/浏览加权 × 时间衰减）、定时快照与热榜接口。热榜不建表
（database.md 取舍 #8 钦定：ZSet 快照 + 定时任务重算）；`lk_post.viewed` 按 M1.1 建表
注释的口径落地为"浏览 UV 快照（HyperLogLog 定时回写）"——M6-A 任务卡预告的
"viewed 为浏览快照（M6-E 换 HLL）"本期兑现。

## 2. 主题内子任务

1. cache-starter 扩 HyperLogLog 分组（m2-1 预留演进点："只增分组不动主接口"）
2. detail() 改造：移除朴素 viewed+1 SQL → PFADD `post:uv:{postId}`（登录用户口径）
3. 热榜分数模型：`score = (liked×5 + comments×3 + uv×1) × e^(-λΔt)`，λ=ln2/半衰期（默认 72h）
4. 定时快照 HotRankJob：候选集（近期 7 天新帖 ∪ 现役榜帖）全量重算 → ZADD `post:hot:top`
   + 跌出候选集 ZREM + PFCOUNT 回写 lk_post.viewed
5. 热榜接口 `GET /api/post/hot?limit=10`（快照 ZSet 读 + listOrdered 回填）
6. 删帖级联补齐：ZREM 热榜 + DEL UV key

## 3. 设计取舍

- **全量重算而非增量累计（与"教科书方案"的对比）**：常见做法是行为发生时 ZINCRBY 累计
  原始分（点赞/评论/浏览三处挂点）+ 定时只补衰减因子——写端重、取消/删除要回退、漂移
  累积。本项目三个行为事实已全部在事实源（liked/comments 同事务冗余维护、UV 在 HLL），
  定时任务直接查事实源算分：**写端零挂点、无回退逻辑、分数永远与事实源对齐**。代价是
  任务端多几次 DB/HLL 读——候选集有界（近期帖+现榜），可接受。"衰减是时间函数非事件
  函数"因此成立得更彻底：分数=f(事实快照, 当前时间)，任何时刻重算都收敛到同一结果
- **半衰期配置而非 λ**：λ=ln2/half-life-hours（默认 72h）——"3 天前的帖权重剩一半"
  业务可直接解释；半衰期即榜单记忆长度（深挖课口径）
- **候选集=近期新帖 ∪ 现役榜帖**：纯近期窗口会让边界帖（恰好 7 天前但仍在榜）瞬断——
  并入现役榜成员，跌出则 ZREM；榜单始终=候选集的完整重算，无残留
- **UV=登录用户口径**：游客（UserHolder null）不计——IP 口径在代理下不准且有 header
  伪造面；演示口径下 UV 即"登录访客数"。HLL key TTL 30 天（衰减窗口外 UV 无用）
- **detail() 移除朴素 viewed+1**：viewed 字段语义收敛为 UV 快照（上次任务回写值），
  消除"PV 累加被 UV 回写覆盖"的双义混乱；M6-A 起 viewed 一直是临时口径
- **权重默认 5/3/1（点赞/评论/浏览）**：互动深度排序 赞>评>看；权重是业务旋钮可配
  （localink.hot.weights），浏览基数大权重低防淹没
- **定时任务照抄 ReconciliationJob 模板**：fixedDelay（上一轮完成再计时，天然防重叠）
  + enabled 开关 + public runOnce() 供测试直调——不引入 cron/分布式调度
- **热榜 score 不暴露**：热度是内部模型口径（权重×衰减是运营旋钮，随时可调），前端按
  序展示；W3 有"热度值"展示诉求再加字段
- **快照 ZSet 与点赞榜的关系**：POST_LIKE_TOP（M6-B）是单一行为的计数榜、事实源
  lk_post_like；POST_HOT_TOP 是多行为加权×衰减的复合榜、事实源=liked/comments/UV 三处
  ——两个榜服务不同页面（帖子卡片角标 vs 热榜页），不合并

## 4. 接口 / Redis Key / 配置

**接口**（PostController 扩 1 个）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/post/hot?limit=10` | 热榜（公开浏览）；List\<PostVO\> 按热度倒序，未过审帖回填时过滤 |

**Redis Key**（KeyManage 登记）

| Key | 模板 | 结构 | TTL | 恢复策略 |
|---|---|---|---|---|
| POST_UV | `post:uv:%s`（postId） | HyperLogLog（member=userId） | 30 天 | 丢失=UV 归零重累，接受（database.md §7） |
| POST_HOT_TOP | `post:hot:top` | ZSet（member=postId，score=加权分×e^(-λΔt)） | 常驻 | 定时任务全量重算 |

**配置**（application.yml `localink.hot`，HotProperties）

| 配置 | 默认 | 说明 |
|---|---|---|
| enabled | true | 快照任务开关 |
| interval-ms | 300000 | 快照周期（5 分钟，fixedDelay） |
| recency-days | 7 | 候选集"近期新帖"窗口 |
| half-life-hours | 72 | 衰减半衰期（榜单记忆长度） |
| weights.like / comment / view | 5 / 3 / 1 | 行为权重 |

## 5. 产出物

| 文件 | 说明 |
|---|---|
| cache-starter `RedisHyperLogLogOps` | HLL 分组（add/count/union → PFADD/PFCOUNT/PFMERGE）+ Default 实现 + RedisCache 访问器（m2-1 预留扩组点兑现） |
| `constant/KeyManage` | 登记 POST_UV（30d）/ POST_HOT_TOP（常驻） |
| `config/HotProperties` | enabled/interval-ms/recency-days/half-life-hours/weights{like,comment,view} |
| `service/HotRankService` + Impl | runOnce（候选集∪→逐帖算分→ZADD/ZREM→PFCOUNT 回写 viewed，变化才落笔防写放大）+ top（快照倒序+listOrdered 回填） |
| `framework/hot/HotRankJob` | @Scheduled fixedDelay + 开关 + 失败告警自愈（ReconciliationJob 同款模板） |
| PostServiceImpl | detail() 朴素 viewed+1 退役→PFADD（游客不计）；delete() 级联 ZREM 热榜+DEL UV |
| `controller/PostController` | GET /api/post/hot |
| application.yml | localink.hot 段 |
| 测试 ×8 | starter HLL 1 例 + HotRankIntegrationTest 7 例；M6-A 老用例 viewed 断言随语义退役更新 |

## 6. 验证记录（2026-09-28 本机实测）

主题 7/7 + starter 1/1；全 reactor **269/269**（261 基线 + HLL 1 + 热榜 7），BUILD SUCCESS；
半衰期数值用例：liked=2/comments=1/uv=1、恰 72h 前发帖 → score=(2×5+1×3+1×1)×0.5≈7.0
精确断言通过（容差 0.05）。

**排障实录**：
①**Spring 的 PFADD 布尔返回不可信**：新元素首添返回 false 但基数已生效（实测
firstAdd=false、countAfter=3）——HLL API 收敛为 void+count 语义，调用方一律以 count
为准，不依赖 PFADD 返回值（这个坑写进接口 Javadoc）
②**selectBatchIds(空列表) 生成 `IN ()` 非法 SQL**：ShardingSphere 解析直接抛
DialectSQLParsingException——首轮快照（现榜为空）必须空守卫；ORM 批查的空集合边界通病
③**M6-A 老用例 viewed=1 断言随语义退役**：detail 后 viewed 不再即时+1（改 UV 快照语义），
断言更新为 0 并注明口径——行为变更要回头校准既有测试，而不是让老断言锁死旧语义

## 7. 学习清单

**核心知识点**
1. **全量重算 vs 增量累计**（本期最大叙事）：教科书方案是行为 ZINCRBY 累计+定时补衰减
   （写端三挂点、取消/删除要回退、漂移累积）；本项目行为事实已在事实源（liked/comments
   冗余计数+UV 在 HLL），定时直查全量重算——写端零挂点、无回退、任何时刻重算收敛同值。
   "衰减是时间函数非事件函数"因此彻底成立
2. **半衰期即榜单记忆长度**：λ=ln2/half-life-hours，默认 72h="3 天前的帖权重剩一半"——
   指数衰减无硬断崖（线性衰减到 0 有），参数业务可直接解释
3. **HLL 的账**：12KB 固定内存估任意基数（百万 UV 的 Set 要几十 MB）、误差 1.04/√16384
   ≈0.81%、只能估数不能列成员；PFADD 返回值经 Spring 不可信（实测）——一律 count
4. **候选集=近期新帖∪现役榜帖**：纯时间窗口会让边界帖瞬断，并入现役榜+跌出 ZREM——
   榜单始终=候选集完整重算，无残留无瞬断
5. **字段语义演进**：lk_post.viewed 从 M6-A 的朴素 PV（临时口径）收敛为 UV 快照——
   一个字段两种口径混用是 bug 之源，演进时显式收敛并同步校准老测试
6. **"DB 事实源+派生视图+可重建"第六次落地**：对账流水/点赞榜/Feed 收件箱/ES/热榜——
   快照失败只告警，下一轮重算自愈

**面试必问题**
1. "热榜怎么设计的？"——行为加权×时间衰减×定时全量重算三件套；追问"为什么不全推增量
   累计"→读写复杂度与漂移对比（事实源直查零漂移）
2. "时间衰减怎么建模？"——score=raw×e^(-λΔt)，半衰期=ln2/λ；追问"半衰期设多少"→
   72h=榜单记忆 3 天，业务旋钮
3. "UV 怎么统计？为什么用 HLL？"——PFADD/PFCOUNT、12KB 固定内存 vs Set 的内存账、
   0.81% 误差可接受；追问"要精确怎么办"→换 Set/Bitmap（内存换精度），声明取舍
4. "老帖会不会一直霸榜？"——半衰期指数衰减+候选集时间窗口双闸：互动跟不上衰减，自然
   跌出
5. "热榜和点赞榜为什么是两个榜？"——单行为计数榜（事实源单一）vs 多行为加权复合榜
   （模型口径），页面语义不同不合并

## 8. 下一步

**M6-F 风控与特色**：DFA 敏感词字典树（同步初筛）+ MQ 异步审核状态机（驳回同步删 ES
文档——复用 M6-D 的 DELETE 同步链路）+ BitMap 用户签到 + GEO 附近商户检索。
