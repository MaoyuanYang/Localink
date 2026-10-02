# 主题任务卡：T2 体检发现全量修复

| 字段 | 内容 |
| --- | --- |
| 主题编号 | T2（测试线第二主题，T1 深度体检的修复轮，同 M8「审计→修复」惯例） |
| 分支 | `feature/t2-audit-fixes`（基于 feature/t1-deep-audit 堆叠，PR 依赖 #86 先合） |
| 状态 | 已完成（2026-10-02） |

## 1. 目标

修复 T1 体检报告（docs/VERIFICATION.md §7 F-1~F-18）全部可修复项：后端 2 处业务代码（异常处理、点赞 ES 同步）、前端 3 处（空值保护、坐标行、分包）、文档 4 处（charset/指标/连接数口径）、CI 与 JaCoCo 接入、数据与环境卫生（孤儿券/快照表/消费组）、验证脚本 part1 全面修绿。含 1 项**勘误**（F-3）。

## 2. 主题内子任务

1. **F-2**：GlobalExceptionHandler 补 MethodArgumentTypeMismatchException → 200+40001（common 模块 spring-webmvc optional 依赖已在位，零 pom 改动）。
2. **F-8**：LikeServiceImpl.like/unlike afterCommit 复用 post-search-sync 发 UPSERT（幂等 indexPost 全量重建）。
3. **F-3 复核勘误**：运行时精确计数证明钳制（1..50）一直存在——T1"全量返回"系截断响应误判，报告勘误+测试强化为可观测断言。
4. **F-5**：清除 20 张孤儿 type=2 券（含 20 条订单/路由/流水，备份表留底）+ 前端三处空值兜底。
5. **F-10/F-15**：删坐标行；manualChunks 分包（app 1.44MB→70KB）。
6. **F-1/F-7/F-18 文档**：导入命令补 charset+原因注记、指标懒注册口径、max_connections 排查条目。
7. **G-3/G-4**：GitHub Actions CI（前端全量+后端编译级）+ JaCoCo（出数不门禁）。
8. **F-16 + 卫生**：清 119 个孤儿消费组、删 _t1_* 快照表、清黑盒自建店铺残留。
9. **part1 全面修绿**：req 改文件体语义 + 11 处调用点转换、3 处字面量断言改精确码、S5k2 userId 契约、S5a2 关注前置时序、自建店铺改名段。

## 3. 设计取舍

- **F-3 走勘误而非修复**：复核证据（50/1/1 条精确计数 + current=1）表明钳制自 M8 起有效——诚实修正审计结论比"顺手加固"更符合验证报告的可信度定位；测试同时强化为钳制可观测断言，防回归。
- **F-8 复用 UPSERT 全量重建而非 ES 部分更新**：post-search-sync 消费端本就以幂等 indexPost 为契约（后到覆盖），点赞后重发一条消息是零新语义的最小改动；painless script 部分更新会引入第二套更新路径。
- **F-5 数据+前端双修**：孤儿券是数据问题（正常代码路径造不出——schema NOT NULL + DTO @NotNull），清数据治本；前端兜底防同类残缺数据再现时口径可见（"时间缺失"）。
- **CI 后端只到编译级**：集成测试需真实中间件与串行独占环境（AGENTS.md §10），GitHub Actions 起 4 容器+并发上下文成本高且口径漂移——编译级门禁（含测试编译）已是 CI 的诚实上限，注释里写明原因。
- **JaCoCo 只出数不做门禁**：真实中间件集成测试语境下行覆盖天然失真（大量框架路径），语义断言才是本项目测试的主体；覆盖率作为参考指标记录。
- **part1 修绿保留"被 part2 覆盖"历史但不再有假失败**：req 文件体语义统一后脚本自包含可独立运行。

## 4. 产出物

| 文件 | 说明 |
| --- | --- |
| `localink-common/.../handler/GlobalExceptionHandler.java`（改） | +MethodArgumentTypeMismatchException→40001 |
| `localink-server/.../service/impl/LikeServiceImpl.java`（改） | like/unlike 后同步 ES（syncLikedToSearch） |
| `localink-web/src/pages/ShopDetailPage.tsx`（改） | 删坐标行 + 秒杀时间空值兜底 |
| `localink-web/src/pages/admin/{AdminOrdersPage,AdminSubscribePage}.tsx`（改） | 活动下拉空值兜底 |
| `localink-web/vite.config.ts`（改） | manualChunks 分包 |
| `pom.xml`（改） | JaCoCo prepare-agent+report |
| `.github/workflows/ci.yml`（新） | 前端全量 + 后端编译级 CI |
| `docs/{deploy.md, middleware-setup.md, iterations/m8-audit-fixes.md, VERIFICATION.md, roadmap.md}`（改） | F-1/F-7/F-18/修复附录/T2 行 |
| `scripts/verify/api-blackbox.sh`（改） | 文件体语义 + 11 处断言/时序修绿 |
| `localink-server/.../test/.../{InputValidationNegativeIntegrationTest, PostSearchIntegrationTest}.java`（改） | 钳制断言强化 + like-ES 同步新用例 |

## 5. 验证记录（2026-10-02 本机实测）

1. **F-2 运行时实证**：`?page=abc` → HTTP 200 `{"code":40001,"message":"参数格式错误: page"}`（修复前 500+40000）。
2. **F-3 复核证据**：`size=100000→50 条 / size=-5→1 / size=0→1 / page=0→current=1`——钳制有效，T1 勘误。
3. **F-8 端到端**：发帖→ES 搜索赞 [0]→点赞→搜索 [1]。
4. **黑盒三段全绿**：part1 **46/46**（T1 时 29/15，修复含自建店铺改名、文件体、时序、精确断言）+ part2 **18/18**（新增 S5a2/S3e2 等检查项）+ part3 **46/46**。
5. **GUI 抽查**：店铺详情坐标行已移除（快照断言）；npm test 32/32；`tsc && vite build` 通过且 app chunk 70KB。
6. **全量回归**：`.\mvnw.cmd clean test` → **346/346 BUILD SUCCESS**（server 246 含 2 skipped 月边界守卫；新增 likeResyncsLikedCountToEs）。
   **JaCoCo 覆盖率（G-4 首次出数）**：全仓**行覆盖 88.9% / 指令 89.1%**——server 88.8%、cache 93.4%、search 100%、delay 91.6%、id 90.9%、ratelimit 87.4%、idempotent 86.6%、mq 86.5%、lock 77.9%（分支覆盖 575/827≈69.5%@server）。踩坑实录：**仓库路径含中文目录时 jacoco exec 静默写失败**（destfile 编码问题），修复为统一落 `${java.io.tmpdir}`。
7. **数据卫生复查**：孤儿券/订单/路由删后残留 0（备份 `_t2_orphan_vouchers`/`_t2_orphan_routes`）；verify-cleanup 复查全 0；Kafka 组 119 孤儿已清仅余 3 现行组。

## 6. 学习清单

**核心知识点**：
1. **审计结论必须可证伪**：F-3 误报的教训——"全量返回"的判断产生于截断响应+未数条数；精确计数（50≠69）是唯一裁决。报告勘误不是丢脸，是审计可信度的一部分。
2. **最小侵入复用既有事件通路**：F-8 没有 new 一个 ES 客户端调用，而是复用 topic+幂等消费契约发一条消息——修复的优雅度在于贴着既有架构缝隙走。
3. **异常处理器的分层口径**：BindException/TypeMismatch/NotReadable → 200+40001（客户端可纠正），NoResourceFound → 404，未知 → 500——统一"参数错误不产生 5xx"的契约本身就是测试断言依据。
4. **vendor 分包的缓存语义**：manualChunks 把 antd/react 拆成独立 hash 稳定块——业务迭代只失效 app chunk（70KB），首屏回访成本骤降。

**面试必问**：
- "你的项目怎么做全局异常处理？"——五类异常三个层级（200+业务码/404/500），参数类型不匹配曾是 500 兜底（体检发现），修复后统一 40001。
- "点赞数在搜索结果里怎么保持新鲜？"——DB 事实源 + Kafka UPSERT 全量重建（幂等后到覆盖），为何不用 ES partial update（第二套更新路径的一致性代价）。
- "审计发现过自己的错误吗？"——F-3 误报实录：结论必须带精确计数证据链。

## 7. 下一步

T 线收官：roadmap 全清 + T1/T2 完成。剩余开放项（均为已声明取舍）：F-9 限流 remoteAddr 口径、G-5 Testcontainers、通知中心演进项、`_t2_*` 备份表确认后可删。
