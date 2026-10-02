# 主题任务卡：W3 社区 + 后台扩展（W 线最大主题）

| 字段 | 内容 |
| --- | --- |
| 主题编号 | W3（Web 前端线第四主题） |
| 分支 | `feature/w3-community`（一主题一分支一 PR） |
| 状态 | 已完成（2026-10-02） |

## 1. 目标

把 M6 社区六主题（内容/互动/Feed/搜索/热榜/风控特色）与 M5 对账体系以页面闭环呈现：社区 C 端（签到卡片 + Feed 关注流 + 热榜 Tab + 发帖图片上传 + 帖子详情两级评论/点赞/关注/共同关注 + ES 搜索高亮页）+ 后台扩展（审核队列观测 + Top 买家看板 + 对账看板）。含 4 个后端补口端点（帖子 admin 分页、对账日志分页、回滚失败表查询、Top 买家昵称回填）。

范围决策（用户已确认）：**一个 W3 一次交付不拆分**；**不做用户主页/粉丝/关注列表页**（后端零接口，补口约 3~4 端点）——人物关系收敛为详情页关注按钮 + 共同关注弹窗，完整用户主页记演进项。

## 2. 主题内子任务

1. **后端 admin 补口**：`GET /api/post/admin/page?auditStatus=`（PostVO 扩 auditStatus）+ `ReconcileAdminController`（对账日志分页 + 回滚失败表）+ Top 买家 Map 回填昵称；全部 @AdminOnly + Service 显式判登录。
2. **前端基础**：types/api 层扩社区全家桶类型（注意社区分页是 PageVO{total,records}，与商户 MP Page 不同）；api 新增 post/feed/search/follow 域；vite proxy 加 `/upload`；导航加"社区/搜索"。
3. **社区页 `/community`**：签到卡片（数字口径，无日历位图）+ Tab（Feed 滚动分页 / 热榜 rank 榜）+ 发帖 Modal（图片上传 ≤9）。
4. **帖子详情 `/post/:id`**：点赞红心 + 关注作者 + 共同关注弹窗 + 两级评论（一级分页 + 楼中楼 + 回复 + 删除）+ 删帖。
5. **搜索页 `/search`**：高亮渲染 + sort 切换 + facet 侧栏 + searchAfter 续翻（公开可搜）。
6. **admin 三页**：审核队列（auditStatus 筛选 + 状态机说明）/ Top 买家（商户+日期）/ 对账看板（流水 Tab + 失败表 Tab + 机制说明）。

## 3. 设计取舍

- **一个主题一次交付（用户决策）**：PR 约 W0~W2 两倍体量，换取社区链路一次闭环验收（"发帖→审核→Feed→搜索→热榜"是 web-frontend.md §7 的 W3 验收原文，拆开则中间态难验收）。
- **人物关系收敛（用户决策）**：后端无粉丝/关注列表、无用户主页、无"是否已关注"接口——详情页关注按钮点击后本地切换态（无回显，刷新重置，如实记录盲区）；共同关注弹窗够演示 SINTER 叙事。
- **签到不做日历格子**：后端 monthDays 是累计天数、无"本月哪几天"位图——卡片显示三数字，日历记演进项（需后端补 BITFIELD 读位图接口）。
- **评论点赞 UI 不做**：后端评论点赞不幂等（连点连加）且无取消——前端不暴露该按钮防脏数据，列表仍显示 liked 计数。
- **审核队列是观测不是操作**：两级审核全自动（显性词同步拒发、隐性词异步驳回），无待审 0 队列（状态机只有 1→2）——admin 页做 auditStatus 筛选列表 + 状态机说明，不做人工通过/驳回（演进项：词库热更新）。
- **对账看板按 id 倒序近似时间序**：lk_voucher_reconcile_log 无 create_time 索引，雪花 id 单调递增可近似；不加索引（动 DDL 超出演示需要）。
- **Feed 用"加载更多"按钮而非无限滚动**：ScrollVO 契约不变（nextCursor=null 停），按钮式实现简单可靠、演示可控。
- **搜索高亮 dangerouslySetInnerHTML**：后端入索引前已 HTML 转义（M6-D 契约），前端直接渲染安全——这是 web-frontend.md §5 明文约定。
- **通知中心不做**：roadmap W3 未点名，GET /api/notice 仅 10 条 JSON 串（无分页/已读），记演进项。

## 4. 接口 / 配置

W3 新增端点：

| 接口 | 方法与路径 | 说明 |
| --- | --- | --- |
| 审核队列（新增） | GET `/api/post/admin/page?auditStatus=&page=&size=` | @AdminOnly；PageVO\<PostVO（含 auditStatus）\> |
| 对账流水（新增） | GET `/api/reconcile/admin/page?status=&page=&size=` | @AdminOnly；ReconcileLogVO 分页 |
| 回滚失败表（新增） | GET `/api/reconcile/admin/failures?page=&size=` | @AdminOnly；RollbackFailureVO 分页 |

消费的既有端点（契约已探明）：post CRUD/comment/like/hot、feed（ScrollVO）、search/post（SearchVO）、follow 三件、user/sign（SignVO）、upload/image、shop/top-buyers。

新增配置：无。

## 5. 产出物

| 文件 | 说明 |
| --- | --- |
| `localink-api-model/.../vo/ReconcileLogVO.java` / `RollbackFailureVO.java` | 对账两表 VO |
| `localink-api-model/.../vo/PostVO.java`（改） | +auditStatus 字段 |
| `localink-server/.../controller/PostController.java`（改）/ `ReconcileAdminController.java`（新）/ `SubscribeController.java`（改，昵称回填） | admin 补口 |
| `localink-server/.../service/PostService+impl / ReconcileAdminService+impl / TopBuyerServiceImpl`（改） | 查询与回填 |
| 测试 `AdminPostPageTest` / `ReconcileAdminPageTest` | 默认上下文，造数断言筛选分页 |
| `localink-web/src/api/{post,feed,search,follow}.ts` | 社区 api 域 |
| `localink-web/src/pages/CommunityPage.tsx / PostDetailPage.tsx / SearchPage.tsx` | C 端三页 |
| `localink-web/src/pages/admin/AdminAuditPage.tsx / AdminTopBuyersPage.tsx / AdminReconcilePage.tsx` | admin 三页 |
| `localink-web/src/{types/api.ts, components/AppLayout.tsx, pages/admin/AdminLayout.tsx, router/index.tsx, vite.config.ts}`（改） | 类型/导航/路由/上传代理 |
| `docs/roadmap.md` / `README.md` | W3 勾选、基线更新 |

## 6. 验证记录（2026-10-02 本机实测）

**测试基线 311 → 316/316**（server 211→216：AdminPostPageTest 2 + ReconcileAdminPageTest 3），`mvnw clean test` 全 reactor 串行 BUILD SUCCESS（跨月签到修复后重跑确认；2 skipped 为月初 Assumptions 保护用例，属预期）。前端 `tsc && vite build` 通过（22s）。

**GUI 联调冒烟**（jar 8086 带 dev 开关 + vite 5173，B/C/D 三账号造数：B 关注 C/D、C 关注 D）清单全部通过：

1. ✅ 社区页：签到卡片"签到成功，已连续 1 天"（数字三口径）→ Feed 出现 C 的新帖（推模式收件箱）+ 图片经 /upload 代理直出 + "没有更多了"（nextCursor=null 到底）
2. ✅ 帖子详情：点赞 ♡0→❤1；一级评论 + 楼中楼回复（"回复 @昵称："渲染）；关注作者（幂等 toast）→ **共同关注弹窗显示 D**（B∩C 关注交集，SINTER 链路）；删除按钮仅本人显示
3. ✅ 两级审核：显性词帖 curl 发帖即拒 30001"内容包含敏感词"；隐性词帖（"稳赚不赔"）3 秒内异步驳回 → 详情 40004"帖子不存在或未过审"→ ES 同步删除（清扫时验证 not_found）
4. ✅ 搜索页：关键词"咖啡"命中 1 条，titleHighlight/contentHighlight `<em>` 高亮 ×6（IK 分词），匿名可搜
5. ✅ 热榜 Tab：衰减公式说明 + 空态（HotRankJob 5 分钟周期未及，页面渲染验证通过，数据依赖 job 周期）
6. ✅ admin 审核队列：驳回帖（audit=2）与在架帖（audit=1）列表正确，在架帖计数"1/2/0"（赞/评/浏，评论数含楼中楼联动）
7. ✅ admin Top 买家：昵称回填生效（"用户9202"等历史+当日数据）、userId 完整精度
8. ✅ admin 对账看板：流水行（库存变化 5→4、待处理状态、时间格式）与机制说明渲染正常
9. ✅ 冒烟数据清扫：两帖（含级联评论/点赞行）、四账号、Redis（feed 收件箱/关注 set/签到/UV/幂等 5 key）、ES 文档、上传图片文件全部删除复查归零

**排障记录**（本主题环境与代码问题都多，实录）：

1. **Windows 排除端口范围吞掉 6379**：Docker daemon 异常重启后 redis 容器宿主映射丢失（`"6379/tcp":[]`），重建报 bind: access permissions forbidden——`netsh show excludedportrange` 确认 6379 落入动态排除区间 6285-6384。修复：提权（UAC）重启 winnat 释放区间 + compose 重建。属本机环境事件，与代码无关。
2. **winnat 重启余波：Docker 端口转发层半死**：MySQL 容器 healthy、docker exec 正常、Test-NetConnection True，但宿主 JDBC 连接被 accept 后 EOF。彻底重启 Docker Desktop（杀进程再启）后恢复（python socket 收到 MySQL greeting 78 字节验证）。
3. **跨月签到测试日期敏感缺陷（既有）**：`crossMonthStreakContinuesIntoPreviousMonth` 硬编码上月最后三天=位 28/29/30 假设 31 天大月——10 月 2 日遇 9 月 30 天时位 30 是不存在日期，续查只连 2 天（预期 5 实际 4）。W2 时 9-30 跑全量恰好绿。修复：按 `lengthOfMonth()` 置位。与 W3 改动无关，首次月初跑暴露。
4. **社区 VO 的 Long id 未字符串化（既有契约缺口，P1）**：PostVO/CommentVO/PostSearchVO 的 id/userId/shopId/parentId/replyId 无 ToStringSerializer → JSON number → JS 精度丢失（19 位雪花尾数变 0）→ 详情页 404。GUI 冒烟实拍发现（Feed 点帖进详情 URL id 尾数 800≠809）。修复：三个 VO 补 @JsonSerialize——与 ShopVO/VoucherVO 等既有约定对齐。M6 时代遗留，W3 前端首次消费才暴露。
5. **PostVO/CommentVO 时间 ISO 'T' 格式（既有）**：无 @JsonFormat 输出 `2026-10-02T15:12:46`，与全局 `yyyy-MM-dd HH:mm:ss` 约定漂移——同批补 @JsonFormat。
6. **AntD Typography 组件不能挂 dangerouslySetInnerHTML**：搜索页 contentHighlight 用 Typography.Paragraph + dangerouslySetInnerHTML → React 报"Can only set one of children or dangerouslySetInnerHTML"整页白屏。修复：改原生 div。铁律：dangerouslySetInnerHTML 只能用于 DOM 元素。
7. **AntD 两字按钮文本自动加空格**："搜索"渲染为"搜 索"，脚本按精确文本匹配点错按钮（点到导航"搜索"）——自动化与肉眼都要注意。
8. **top-buyers Map 的雪花 userId 精度**：Map 不走 ToStringSerializer，Controller（JSON 边界）转 String，Service 层测试消费 Long 不受影响。
9. **冒烟脚本连续造号撞 IP 发码限流（10/min）**：多账号冒烟需错峰（sleep 窗口）或复用已登录 token（GUI 注入 localStorage 方式）。

## 7. 学习清单

**核心知识点**

1. **ScrollVO 滚动分页的前端契约**：lastScore=null 从头开始（不是空页）、nextCursor 原样回传、null 到底即停——与 page/size 分页的适用分野（时间线 vs 列表）。
2. **两级审核的页面投影**：同步初筛（DFA 显性词，发帖即拒 30001）+ 异步复审（隐性词，驳回即从详情/Feed/热榜/ES 全端消失表现为 404）——"先发后审"让正常内容零延迟，风险内容存活秒级。
3. **搜索三段式的前端消费**：query 算分 / post_filter 保聚合全集 / highlight 已转义可直渲——facet 侧栏与结果集的独立性是 post_filter 语义的直观呈现。
4. **对账体系的可观测面**：Redis 流水↔DB 单向比对（宽限期只护差异判定）、status 1→4 翻牌、差异补偿复用统一回滚、失败表带行龄重试——admin 页呈现的是"最终一致性的账本"。
5. **推拉结合 Feed 的读端形态**：收件箱 ZSet（score=毫秒<<12|雪花低 12 位）滚动分页 + 大 V 读时拉取读端归并——页面上的"加载更多"背后是位账与归并。

**面试必问题**

1. "Feed 为什么用游标不用 offset？"——写扩散收件箱按 score 排序，offset 翻页会因新帖插入导致重复/漏帖；游标（exclusive 上界）以 lastScore 定位无此问题；同分用位账拼唯一 score（毫秒 41 位+雪花低 12 位）。与 SQL keyset/ES search_after 同构。
2. "评论怎么设计的？"——两级上限（一级+楼中楼），一级分页 children 全量附带；点赞走事实表+同事务冗余计数+afterCommit ZSet 榜；评论点赞刻意不幂等是已知债务（前端不暴露按钮规避）。
3. "驳回一个帖子要清多少地方？"——DB audit=2 条件 UPDATE + 点赞榜 ZREM + 热榜 ZREM + UV DEL + ES 文档删除（复用 PostDeletedEvent）——派生视图"DB 事实源+可重建"的一致性口径。

## 8. 下一步

W4 打磨：加载/空态、README、nginx 部署说明（前端线收官）。
