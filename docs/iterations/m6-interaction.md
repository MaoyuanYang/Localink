# 主题任务卡：M6-B 互动关系（点赞 + 点赞榜 + 关注）

| 字段 | 内容 |
|---|---|
| 主题编号 | M6-B |
| 分支 | `feature/m6-interaction`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-26） |

---

## 1. 目标

互动三件套（原 M6.4/M6.5，F-COM-01 后半 + F-COM-02）：帖子点赞（DB 事实表 + 冗余计数
+ ZSet 榜单）、点赞榜 TopN、关注/取关/共同关注（Set 交集）。`lk_post_like` / `lk_follow`
表（M1.1 建）首次启用；`lk_post.liked`、`lk_user.fans/followee` 三个预留冗余字段首次启用。

## 2. 主题内子任务

1. 点赞：赞/取消（lk_post_like 事实行 + liked 同事务增减 + 榜单 ZINCRBY）；重复点赞幂等
2. 点赞榜 TopN：ZREVRANGE + 帖子信息回填（复用 PostVO），limit 上限 50
3. 删帖级联：点赞行物理删 + 榜单 ZREM（补齐 M6-A 的级联矩阵）
4. 评论点赞：仅计数不入榜（database.md 4.8 既有决策，无事实表）
5. 关注/取关：lk_follow 行 + fans/followee 双侧计数同事务 + 关注 Set SADD/SREM；幂等口径同点赞
6. 共同关注：SINTER 两用户关注 Set + 昵称回填（UserBriefVO）
7. 帖子详情回填 meLiked（当前用户是否已赞；未登录为 null）

## 3. 设计取舍

- **DB 事实源 + ZSet 加速层（database.md 取舍 #5 既有决策）**：点赞事实行落 lk_post_like
  （唯一索引 uk_post_user 兜底一人一赞），ZSet 榜单只承担 TopN 排序——纯 Redis 方案重启/故障
  丢事实，DB 表保障可恢复，代价是点赞多一次 DB 写，演示场景可接受
- **互动写操作幂等化（与秒杀"一人一单报错"对比）**：重复点赞/重复关注/取消未赞=直接返回成功、
  不重复计数（catch DuplicateKeyException 短路，计数与 Redis 增量都不执行）。秒杀重复下单
  报错是因为"每人限一单"是业务惩罚规则；互动无惩罚语义，幂等比报错体验好。防重不靠
  catch——靠唯一索引，catch 只是优雅兜底
- **榜单/集合增量放事务提交后（afterCommit）**：事实行 + 冗余计数同事务提交，ZINCRBY/SADD
  注册到 afterCommit 回调——Redis 先行则 DB 回滚后榜单凭空多分；afterCommit 后进程仍可能
  崩溃漏加，漏了也只是榜单漂移，事实源可重算恢复（对账体系同款心智：DB 事实源 + 派生视图
  + 可重建）
- **score≤0 即 ZREM**：取消点赞 ZINCRBY -1 后 score 可能到 0/负（含"榜单无此 member 被
  ZINCRBY 负增量新建"的场景），残留 member 会占 TopN 名额，ZREM 防僵尸
- **关注 Set 只建"我关注谁"，不建粉丝 Set**：followee 集合上界=该用户主动关注数（普通用户
  几百），粉丝集合可能巨型（大 V 百万）——粉丝数走 User.fans 冗余计数，粉丝明细走
  lk_follow idx_follow_user_id 反查；M6-C Feed 推模式对大 V 改拉模式，正是绕开巨型粉丝集合
- **fans/followee 非实时精确**：条件递减（WHERE fans>0）防负数，漂移接受（database.md 4.1
  已注明）——冗余计数的服务对象是展示，不是对账
- **评论点赞仅计数不入榜**：无 lk_comment_like 事实表=同一人可重复赞（演进声明：防重需
  事实表，评论点赞无榜单诉求、量级低，不值一张表）。与帖子点赞构成"事实源选择"的两个档位
  对比：要防重/要入榜→事实表；只要数字→纯计数
- **meLiked 只在 detail 回填**：page 列表逐帖查 per-user 状态是 N 次查询（演进声明：列表页
  批量判重待 W3 有真实页面诉求时再做批量接口）；未登录 detail 返回 null 而非 false，
  前端可区分"未登录"与"未赞"
- **删帖级联的 ZREM 走事务内（非 afterCommit）**：删帖与 ZREM 之间崩溃，两种残局都由
  "top 按现存帖回填 + 事实源重算"兜底，取更简单的同事务写法

## 4. 接口 / 表 / Redis Key

**接口**（7 个：PostController 扩 4 个 + 新 FollowController 3 个）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/post/{postId}/like` | 点赞（幂等）；返回更新后 liked |
| DELETE | `/api/post/{postId}/like` | 取消点赞（幂等）；返回更新后 liked |
| GET | `/api/post/like/top?limit=10` | 点赞榜 TopN（limit 1~50，未过审帖不回填） |
| POST | `/api/post/comment/{commentId}/like` | 评论点赞（仅计数，不入榜） |
| POST | `/api/follow/{userId}` | 关注（幂等；禁自己；目标须存在） |
| DELETE | `/api/follow/{userId}` | 取关（幂等） |
| GET | `/api/follow/common/{userId}` | 共同关注（需登录，SINTER+昵称回填） |

**表**（M1.1 已建，本期首次启用，无 DDL 变更）：lk_post_like、lk_follow；冗余字段启用
lk_post.liked（M6-A 已初始化 0）、lk_user.fans/followee。

**Redis Key**（KeyManage 登记）

| Key | 模板 | 结构 | TTL | 恢复策略 |
|---|---|---|---|---|
| POST_LIKE_TOP | `post:like:top` | ZSet（member=postId，score=点赞数） | 常驻 | 由 lk_post_like 重算 |
| USER_FOLLOWEE | `user:follow:%s`（userId） | Set（member=被关注 userId） | 常驻 | 由 lk_follow 重算 |

## 5. 产出物

| 文件 | 说明 |
|---|---|
| entity+mapper | PostLike（lk_post_like）/ Follow（lk_follow），两表首次启用 |
| `constant/KeyManage` | 登记 POST_LIKE_TOP / USER_FOLLOWEE（含语义与恢复策略注释） |
| service+impl | LikeService（赞/取消/TopN/评论赞）/ FollowService（关注/取关/共同关注） |
| `service/impl/TxCallbacks` | afterCommit 回调包内共用工具（M6-B 统一时序口径） |
| `controller/PostController` | 扩 4 端点：赞/取消/点赞榜/评论点赞 |
| `controller/FollowController` | 新建 3 端点：关注/取关/共同关注 |
| api-model | UserBriefVO 新增；PostVO 增 meLiked（Boolean，仅详情回填） |
| PostServiceImpl | 删帖级联清点赞行+榜单 ZREM；detail 回填 meLiked；listOrdered 按序回填（榜单复用） |
| 测试 ×7 | InteractionIntegrationTest：赞/取消旅程（计数+榜单+meLiked+双侧幂等）/TopN（倒序+limit+未过审过滤）/删帖级联/评论点赞仅计数/关注取关旅程（双侧计数+Set 同步+幂等）/关注自己与不存在用户拒/共同关注（交集+昵称回填+未登录拒） |

## 6. 验证记录（2026-09-26 本机实测）

主题 7/7；全 reactor **245/245**（238 基线 + 7），BUILD SUCCESS；KeyManage 契约锁测试通过
（新增两个 key 不破坏既有登记）。

**实现与测试注意点**（本主题一次通过，无排障实录，留两条经验）：
①USER_FOLLOWEE 集合按 userId 寻址，而测试用户每轮重建（雪花 id 变化）——AfterEach 必须按
本例登录过的 userId 逐个清 key，否则残留集合永不清除（无 TTL 常驻 key 的泄漏面）；
②榜单排序断言需要两个不同用户点赞同一帖构造分差——登录准备从单用户泛化为 loginAs(phone) 复用。

## 7. 学习清单

**核心知识点**
1. **DB 事实源 + Redis 派生视图（项目第三次出现）**：对账流水、ES 索引、点赞榜共享同一心智——
   DB 落事实、Redis 做加速视图、漂移与故障由重算兜底。纯 Redis 方案重启丢事实是反面教材
2. **幂等的两档口径**：互动类（点赞/关注）唯一索引兜底 + catch 短路幂等返回；秒杀一人一单
   报错——差异在"重复"是否业务惩罚语义，防重都不靠先查后插的判断（并发缝隙），靠约束
3. **afterCommit 时序**：Redis 增量放事务提交后——先行则回滚留幻影分；afterCommit 后崩溃
   只是漏加（可重算）。与"DB 先行还是 Redis 先行"的通用权衡同构
4. **ZINCRBY 负增量的暗坑**：对不存在的 member ZINCRBY -1 会"新建"出 -1 分 member——
   score≤0 必须 ZREM，否则僵尸 member 占 TopN 名额
5. **集合上界分析决定建不建**：followee Set 上界=主动关注数（可建），fans Set 上界=粉丝数
   （大 V 百万，不建）——M6-C Feed 推挽结合的直接伏笔
6. **冗余计数的服务对象是展示**：同事务增减保证大势正确、条件递减防负数、漂移接受——
   不是对账口径，不追求精确

**面试必问题**
1. "点赞怎么设计的？"——事实表+冗余计数+ZSet 榜三件套；追问"为什么不全放 Redis"→
   重启丢事实、DB 保障可恢复、榜单只是加速层可重算
2. "重复点赞怎么处理？"——uk_post_user 唯一索引兜底 + catch DuplicateKeyException 幂等返回；
   追问"和秒杀防重复下单什么区别"→同款约束思想、不同反馈口径（惩罚规则 vs 幂等体验）
3. "Redis 榜单和 DB 点赞数怎么保持一致？"——DB 同事务先行、ZINCRBY 走 afterCommit、
   漏加漂移由事实源重算——追问"这不就是你秒杀对账的思路吗"→是，同一套心智第三落地
4. "共同关注怎么实现？"——两用户关注 Set SINTER 交集 + 昵称批量回填；追问"粉丝集合为什么
   不建"→上界分析（大 V 巨型集合），粉丝走 DB 反查+fans 计数，Feed 拉模式伏笔
5. "评论点赞和帖子点赞为什么不一个做法？"——事实源选择分档：要防重要入榜→事实表；
   只要数字量级低→纯计数——按诉求付成本，不做过度设计

## 8. 下一步

**M6-C Feed 流**：推模式（发帖推送到粉丝收件箱）+ 关注流滚动分页（score 游标）+ 推挽结合
（大 V 拉、普通用户推）——粉丝明细反查与"不建巨型粉丝 Set"的伏笔在本期埋下。
