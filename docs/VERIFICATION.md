# Localink 项目验证报告（T1 深度体检 · 2026-10-02）

| 项 | 值 |
|---|---|
| 验证日期 | 2026-10-02 |
| 验证基线 | main @ `33282fc`（PR #85 合并后）；本轮工作在 `feature/t1-deep-audit` 分支产出验证资产 |
| 验证范围 | **W0~W4 前端线 + W1~W3 补口端点首次运行时验证**（上轮 M8 审计时未启动）；M8 修复抽查复核；**既有测试方案合理性评估**；新增两块测试角度（后端安全/负面矩阵、前端 Vitest 单测） |
| 验证方式 | 基线全量回归复跑 → 启动自检证据采集 → 黑盒三段（part1 复跑 / part2 修复复跑 / part3 新增）→ GUI 黑盒走查 17 页（browser-use）→ M8 修复运行时抽查 → 测试方案评估 → 新增测试资产并跑绿 |
| 结论 | **交付真实可信且质量较 M8 后进一步提升**：316/316 基线复跑全绿；W0~W4 全部 17 页 GUI 走查通过（含秒杀两步流、对账看板实时一致性等跨功能闭环）；黑盒 61 项断言通过。**发现 1 项 P1 文档级问题（种子导入命令导致全新环境中文全乱码）**、2 项 P2、若干 P3；同时修复 4 处验证脚本自身缺陷并修复本机被污染的种子数据 |

> 与上轮（2026-09-29 M8 审计，见 §11 归档）的边界：本轮**不重复** M0~M7 逐主题核验与 JMeter 压测复跑（热路径自 M8 后未变更，性能档案沿用 m7-load-test 与 M8 §4.2 复跑数字）；聚焦 W 波次运行时验证 + 测试体系本身。

---

## 1. 基线复验（declared verification）

| 承诺命令 | 结果 |
|---|---|
| `docker compose up -d mysql redis kafka` + `--profile es` | ✅ 四容器 healthy（localink 15 表 + localink_1 4 物理表结构完好，保留既有数据 67 店铺/405 用户） |
| `.\mvnw.cmd clean test`（AGENTS.md §10 串行独占口径） | ✅ **316/316 全绿 BUILD SUCCESS（6:52）**，分模块 cache 52 / lock 9 / idempotent 6 / ratelimit 16 / mq 4 / delay 3 / id 9 / search 1 / server 216（含 2 skipped = 月边界 Assumption 守卫，与 W4 卡记录一致） |
| `localink-web: npm install && npm run build` | ✅ tsc + vite build 通过（1560 模块，21s；⚠️ 产物单 chunk 1.44MB 未分包，P3 见 F-15） |
| 启动自检四条日志（deploy §6） | ✅ 库存回灌 upcomingCount=0 / 布隆 count=69 / 对账完成差异补偿=0笔 / GEO total=69 indexed=13（indexed<total 属历史数据无坐标，非缺陷） |
| Kafka 三消费组 | ✅ localink-server-seckill-order / -post-audit / -post-search（另有孤儿消费组 400+，M8 已知 P3 仍在，见 §7-F16） |
| `/ping` → pong | ✅ |

## 2. 黑盒三段（scripts/verify/）

| 脚本 | 结果 | 说明 |
|---|---|---|
| part1（M8 原版复跑） | 29 PASS / 15 FAIL | 15 项失败**全部为脚本已知陈旧**（字面量比较 bug、缺 M6 必填字段、GBK 终端编码、时序），由 part2 修正覆盖；后端实际行为均正确（40008/40004/40003 返回无误） |
| part2（本轮修复后） | **15 / 15 全绿** | 修复三处脚本缺陷后全绿（见 §6 脚本修复） |
| **part3（本轮新增）** | **46 / 46 全绿** | W1~W3 补口端点全覆盖：我的订单、admin 订单监控+手动关单幂等+双端库存回补、对账看板、订阅/统计、Top 买家（userId 字符串化实证）、通知 40002、评论分页/评论点赞/审核队列筛选、Feed 游标滚动两页无重复、匿名越权负面矩阵 9 项 |

关键运行时证据：**手动关单 → DB 库存 1→2 回补 + Redis 库存同步回补 + 二次关单返回 false（幂等闸门）**；跨功能链路（建券→秒杀两步→订单→关单→回流）全程无人工干预跑通。

## 3. 前端 GUI 黑盒走查（W0~W4 首次运行时验证，browser-use）

环境：后端 8086（`--localink.dev.sms-code-query.enabled=true`）+ Vite dev 5173。逐页 DOM 快照 + 关键节点截图留档。

### 3.1 C 端 8 页（全部通过）

| 页面 | 验证点 | 结果 |
|---|---|---|
| LoginPage | dev SMS 自动回填验证码、发送按钮 40s 倒计时禁用、登录跳首页 | ✅（截图留档） |
| ShopListPage | 69 家分页、类型筛选下拉、**修复后种子数据正常显示**（山语茶餐厅/武林广场/¥88.00 分转元） | ✅ |
| ShopDetailPage | 详情表（评分/月售/评论/均价/地址/营业时间）、秒杀活动列表带状态标签 | ✅（23 活动） |
| SeckillDetailPage | **实时倒计时（01:56:18 跳动）、两步流 UI**：申请令牌→「令牌剩余 29s」→立即抢购→成功跳订单页 | ✅（截图留档） |
| MyOrdersPage | 异步建单提示+刷新按钮、新订单 3s 内可见（订单号 17 位字符串化无精度丢失，JSON 实证 `"id":"9946..."`） | ✅ |
| CommunityPage | 签到闭环（状态翻转+按钮禁用+toast）、发帖（先发后审文案）、热榜（点赞后下个 5 分钟重算周期入榜 #1，服务端日志 written=1 实证） | ✅ |
| SearchPage | ES 搜索命中 + `<em>` 高亮 + 元信息 | ✅ |
| PostDetailPage | 点赞状态（API 点赞在 GUI 同步显示）、评论发布即显、作者删除按钮 | ✅（截图留档） |

### 3.2 admin 区 9 页（全部通过）

登录→Layout 侧边 7 菜单→逐页走查：商户管理（表格+新建/编辑/删除）、券与活动（普通券/秒杀双 tab）、订单监控（双下拉选活动→订单表，M4 历史券 2 单「超时关闭」状态与 15min 延迟关单口径相符）、订阅提醒、审核队列（GUI 新帖在列「在架 1/1/1」）、Top 买家（GUI 用户当日 1 单排 #3，userId 字符串化）、对账看板（**GUI 秒杀订单 99460338113626112 扣减 2→1(-1) 状态「一致」实时可见——秒杀→订单→对账全链路闭环铁证**）。

### 3.3 异常路径

未登录访问 `/orders` → 拦截器清登录态并跳 `/login` ✅（40002 分流前端侧兑现）。

### 3.4 GUI 观察项（非阻断）

- 活动下拉对 beginTime 为 null 的券显示「（null 起）」——M8 残留券数据残缺 + 前端无空值保护（F-5）。
- 搜索结果赞数是 ES 索引时快照，点赞后不回更（F-8）。
- 店铺详情向 C 端暴露原始经纬度（F-10）。
- C 端店铺详情可见 23 张历史测试券（M4/M8 命名，环境数据积累，非本轮产生）。
- 方法论备注：Ant Select 浮层在 IAB webview 中对 Playwright actionability 检查受限，改用坐标路径完成交互——属自动化环境限制，非产品缺陷。

## 4. M8 修复抽查复核

| M8 项 | 复核方式 | 结果 |
|---|---|---|
| A-5（notice:sent 永不过期） | Redis 实测 TTL | ✅ 键均带 ~20h TTL（1 天口径），无永生键 |
| A-7（@AdminOnly 闸门） | 新增 `AdminGuardMatrixIntegrationTest` 9 用例（guard ON 上下文） | ✅ 匿名 40002 / 普通用户 40003 / 白名单管理员放行，全端点矩阵 |
| A-2（关单幂等+回补） | part3 运行时（§2） | ✅ |
| D-14（id 字符串化） | part3 + GUI + JSON 实测 | ✅ top-buyers/订单/共同关注全字符串化 |

## 5. 既有测试方案合理性评估（本轮核心诉求之一）

**结论：作为「个人作品集的后端语义验证体系」合理性很高；作为「可持续回归安全网」存在结构性缺口——本轮已补齐其中两个最高优先级缺口。**

### 5.1 优点（保持）

1. **真实中间件集成测试**（非 mock 堆）：48 个 `@SpringBootTest` 类直连 MySQL/Redis/Kafka/ES，断言 DB 行内容、Redis key 形态、Lua 返回契约——这是 316 个测试可信度的根基。
2. **并发与一致性语义有真测试**：超卖（8 线程抢末位库存恰扣一次）、一人一单（8 并发 1 成功 7 拒 10005）、幂等/退避/回滚/对账全链路、spy 验证「两次 miss 恰一次 DB 命中」。
3. **串行纪律文档化**（AGENTS.md §10，两次事故实证）——本身体粗但诚实。
4. 黑盒脚本自带清扫协议（物理表口径+复查计数不信返回码）。

### 5.2 缺口与处置状态

| # | 缺口 | 影响 | 处置 |
|---|---|---|---|
| G-1 | **前端 0 测试**（39 源文件） | W0~W4 交付无自动化证据，回归全靠手工 | ✅ **本轮已补**：Vitest + RTL + jsdom，32 用例（相位/格式化/authStore/请求拦截器含 40002 分流/页面冒烟），`npm test` 10.8s |
| G-2 | **无安全/负面测试**（参数边界/越权矩阵/上传加固/token 生命周期/payload 安全零覆盖） | 输入异常与越权回归无保护 | ✅ **本轮已补**：后端 5 测试类 29 用例（见 §6） |
| G-3 | 无 CI 流水线 | badge 手工维护，回归依赖自觉 | 未做（用户选择），建议后续 GitHub Actions：前端 lint+build+test、后端编译级 |
| G-4 | 无覆盖率工具 | 316 只是数量指标 | 未做（用户选择），建议 JaCoCo 一次性出数 |
| G-5 | 无测试环境隔离（无 test profile/Testcontainers，强耦合本机 docker，只能串行） | 套件不可迁移、不可并行 | 记录为已知取舍（作品集语境可接受）；远期 Testcontainers |
| G-6 | common/api-model 0 测试、无 WebMvcTest 切片 | 单元层薄弱 | 低优先级记录 |
| G-7 | 黑盒脚本未纳管（last-run.env 缺失、无运行说明入口） | 可重复性靠口口相传 | part3 已带退避重试与自建数据口径；建议 README 补 verify 脚本运行节 |
| G-8 | part1 脚本与 schema/契约脱节仍在库（15 项假失败） | 误导后来者 | 本轮已修关键两处（表名/字段名）；part1 其余陈旧项由 part2 覆盖的口径已在脚本头注明 |

## 6. 新增测试资产（本轮交付）

### 6.1 后端安全/负面矩阵（localink-server，5 类 29 用例，全绿）

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `web/InputValidationNegativeIntegrationTest` | 9 | 不存在资源 40004、畸形 JSON 40001、缺参 40001、空体、未知路由 404+40004、分页极端值不 5xx、**page=abc 落 500 钉死现状**（F-2） |
| `web/AdminGuardMatrixIntegrationTest` | 9 | guard ON 上下文：匿名/平民/管理员 × post-admin/order-admin/close/reconcile/subscribe-stats 全矩阵（40002/40003/0 精确断言） |
| `web/UploadHardeningIntegrationTest` | 5 | 匿名 40002、非图片扩展名/无扩展名/空文件 40001、**路径穿越中性化**（UUID 重命名+落盘仍在根目录内断言） |
| `user/TokenLifecycleIntegrationTest` | 4 | 过期拒绝、**注销后 token 失效**、滑动续期（压短 TTL→中途访问→超原 TTL 仍有效的行为学验证） |
| `web/PayloadSafetyIntegrationTest` | 2 | SQL 注入片段/XSS 片段落库回读原样往返+表结构无恙（参数化查询实证） |

### 6.2 前端 Vitest（localink-web，32 用例，10.8s 全绿）

`utils/seckillPhase`（8：相位边界含 now==begin/end、非法时间串）、`utils/format`（9）、`stores/authStore`（4：含 persist 落 localStorage）、`api/request` 拦截器（8：**Result 解包、40002 清态+C 端/admin 双跳转、不重复跳转、token 注入无 Bearer 前缀**，自定义 axios adapter 方案）、页面冒烟（LoginPage 2 / ShopListPage 1 含 mock 数据渲染+分转元）。新增 devDeps：vitest/jsdom/@testing-library。

### 6.3 黑盒与脚本

- **新增 `scripts/verify/api-blackbox-part3.sh`**（46 断言，W1~W3 端点全覆盖，登录带限流退避重试）。
- **修复 part2 三缺陷**：券查询表名错（`lk_seckill_voucher` 无 title 列→`lk_voucher`）、还原顺序（先读后改，曾致 shop1 名连环污染）、共同关注断言字段（`id`→`userId`，D-14 后契约）；S1 段重写为**自建店铺生命周期**（建→读验 bloom-after-insert→改名→缓存失效→删），不再触碰种子店铺。
- **修复 part1 表名**（同 part2）；**修复 cleanup ES 索引名**（`lk_posts`→`post`，F-6）。

## 7. Findings（分级，只记录；标 ✅ 者为本轮已作为验证资产/数据修复）

| # | 级 | 领域 | 发现 | 证据 |
|---|---|---|---|---|
| F-1 | **P1** | 文档/数据 | **README 与 deploy.md 的种子导入命令缺 `--default-character-set=utf8mb4`**：Windows 下 `docker exec -i mysql ... < sql/localink.sql` 以 cp1252 解读 UTF-8 流，**全新环境种子数据中文全双重编码乱码**（本机 10 店铺 name/area/address + 10 类型名中招，GUI 乱码直达 C 端） | 库内 HEX 逐位比对（C3A6C2… 双重编码形态）+ sql 文件本身干净（E5B1B1）+ 修复后与种子源逐字一致。**数据已修 ✅**（cp1252 反向映射还原，快照表 `_t1_shop_bak`/`_t1_shoptype_bak` 留底）；**文档待修**：两处命令加 charset 参数 |
| F-2 | P2 | 后端 | `MethodArgumentTypeMismatchException` 无专属 handler，`?page=abc` 落兜底 **HTTP 500 + 40000**（应 40001） | 运行时探针实测；钉死测试在位（修复后应改断言并删本条） |
| F-3 | P2 | 后端 | 分页参数无钳制：`size=-5 / 0 / 100000` 均 200 全量返回（69 家全回），可被恶意拉全表 | 运行时探针实测（AGENTS.md §6 DTO 校验条款未覆盖 @RequestParam） |
| F-4 | P2 | 验证资产 | part1/part2 券查询用错表（M8 起 P2-2 段全数假失败，`2>/dev/null` 吞掉 unknown column 错误） | 本轮定位修复 ✅ |
| F-5 | P3 | 前端 | 活动下拉「（null 起）」：M8 残留券 beginTime 为 null，前端无空值保护 | GUI 快照 |
| F-6 | P3 | 验证资产 | cleanup 的 ES 兜底清理索引名错（`lk_posts`），**自 M8 起静默无效**（未暴露因帖子均走 API 删除带同步） | 运行时报 no such index；已修 ✅（实索引名 `post`） |
| F-7 | P3 | 文档 | actuator 承诺 4 指标中 3 个为事件触发式懒注册（rollback.failure/reconcile.compensated/order.close 仅对应事件路径计数，无事件则不可见）；「指标存在性」口径需注记 | /actuator/metrics 实测 + 代码增量点核对 |
| F-8 | P3 | 后端口径 | 搜索结果赞数为 ES 索引时快照，点赞后不回更（M8 已知 P3「ES liked 快照失真」的 GUI 侧表现） | GUI 实测（点赞后搜索仍显示赞 0） |
| F-9 | P3 | 验证资产 | IP 维度限流键取 `getRemoteAddr()`（XFF 不生效），本地连跑多脚本共享回环桶互相干扰 | 服务端日志 `key=ip:0:0:0:0:0:0:0:1`；part3 已加退避重试 ✅ |
| F-10 | P3 | 前端 | 店铺详情向 C 端用户展示原始经纬度（开发者视角字段） | GUI 快照 |
| F-11 | 观察 | — | 热榜空是旧帖零互动的自然衰减（公式 72h 半衰期），机制正常：点赞后下个重算周期 written=1 入榜 | 服务端热榜快照日志 |
| F-12 | P3 | 验证资产 | part2 旧版改名测试曾污染 shop1（address=x/avgPrice=100/北京坐标），多轮累积后名字连环污染 | 数据考古（M8 期起）；脚本已改自建店铺 ✅ + shop1 已按种子全字段还原 ✅ |
| F-13 | P3 | 文档 | README badge「316/316」未注明 2 skipped（月边界 Assumption 守卫） | surefire 报告 |
| F-14 | 观察 | 前端 | 通知接口可用（part3 P3-6 验证）但前端无页面消费——W3/W4 已声明的 skip，PRD 表未标注 | 页面清单核对 |
| F-15 | P3 | 前端构建 | vite 产物单 chunk 1.44MB（gzip 458KB）未分包，首屏加载偏重 | build 警告 |
| F-16 | P3 | 环境 | 孤儿 Kafka 消费组 400+（M8 已知，cache-invalidation 随启停累积）仍在 | kafka-consumer-groups 列表 |
| F-17 | P3 | 文档 | README 开发状态段遗留「M0~M4 已全部收官」「Web 前端线进行中」陈旧表述（M8 期残留，与同段收官表述并存） | 探索期发现；**已顺带修复 ✅**（与 badge 同段的本主题直接产物） |
| F-18 | P3 | 测试环境 | 新增 guard-ON 上下文后套件同时存活的 Spring 上下文触及 MySQL `max_connections=151` 上限（AGENTS.md §10「Too many connections」敏感性的再现：触发点=测试类数×上下文缓存） | 首轮回归 9 Errors 实录；处置：本机 `SET GLOBAL max_connections=300` 后复跑全绿 ✅（环境级参数，建议 middleware-setup 注记） |

## 8. Unverified 清单

| 项 | 原因与替代证据 |
|---|---|
| 15 分钟延迟关单全时长生命周期 | 未等满真实 15 分钟；以 M4 历史订单「下单→关闭 +17min」时间线 + OrderCloseIntegrationTest + part3 手动关单同链路佐证 |
| 5MB 上传上限 | 容器层 multipart 解析，MockMvc 不经过；配置在位（application.yml 15-16 行），由部署口径担保 |
| 双实例缓存失效广播 | 单实例环境（M8 已声明，口径不变） |
| JMeter 性能复跑 | 热路径自 M8 后未变更（W 波次仅增查询/管理端点），沿用 m7-load-test 与 M8 §4.2 复跑数字（用户选定不跑） |

## 9. 验证资产与数据处置

| 资产 | 说明 |
|---|---|
| `scripts/verify/api-blackbox-part3.sh` | 新增：W 波次端点黑盒（46 断言） |
| `localink-server/src/test/.../web/InputValidationNegativeIntegrationTest.java` 等 5 类 | 新增：安全/负面矩阵 29 用例 |
| `localink-web/src/**/__tests__/*.test.ts(x)` + vitest 配置 | 新增：前端单测 32 用例 |
| part1/part2/cleanup 脚本修复 | 见 §6.3 |
| GUI 走查截图 | ZCode 工件目录留档（登录/秒杀详情/评论/对账看板等关键节点） |

数据处置：黑盒与 GUI 测试数据已全部清扫（verify-cleanup 复查归零 + GUI 帖/用户手清）；**种子数据 10 店铺+10 类型已从 cp1252 双重编码还原为与 sql 源逐字一致**（还原前快照 `_t1_shop_bak`/`_t1_shoptype_bak` 两表留底，确认无误后可删）；业务代码零改动。

## 10. 建议的后续工作（优先级序）

1. **F-1 文档修复（一行×2 处）**：README 快速开始 + deploy.md §3 两条导入命令补 `--default-character-set=utf8mb4`——这是唯一影响「全新环境首装演示」的 P1。
2. **F-2/F-3 后端加固**：全局异常处理器补 `MethodArgumentTypeMismatchException`→40001；分页参数钳制（size∈[1,100]）——钉死测试已就位，改动即验证。
3. F-5 前端空值保护（活动下拉 beginTime null 兜底显示「未设置」）。
4. G-3/G-4：GitHub Actions（前端全量+后端编译级）与 JaCoCo 覆盖率一次性出数。
5. F-7/F-13 文档口径注记（指标懒注册、badge 2 skipped）。
6. 环境卫生：删 `_t1_*` 快照表（用户确认后）、清孤儿消费组。

---

## 11. 归档：上一轮审计（2026-09-29，M8 前后）

上轮对 M0~M7 全部 23 主题全量核验：287/287 全绿、23 Verified（11 带瑕疵星标）、12683 QPS 头条复现；发现 7 项 P1（A-1 幻影扣减 / A-2 关单非原子 / A-3 对账翻牌 / A-4 重建竞争 / A-5 SETNX 无 TTL / A-6 GEO 启动 DoS / A-7 零权限区分）、19 项 P2、约 40 项 P3、19 项文档漂移（D-1~D-19）。**全部已由 `feature/m8-audit-fixes` 修复**（7 P1 + 19 P2 + 加固 P3 + D-1~D-19 + 9 项回归测试 → 296 基线），逐项映射与设计取舍见 `docs/iterations/m8-audit-fixes.md`。本轮 §4 对其中 A-2/A-5/A-7/D-14 做了运行时/新增测试复核，全部成立。

---

*报告由 project-verify 审计流程生成（T1 深度体检主题）。代码位置以 main @ 33282fc 为准；新增测试与脚本修复在 feature/t1-deep-audit 分支。*
