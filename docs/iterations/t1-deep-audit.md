# 主题任务卡：T1 深度体检 + 测试方案评估 + 新增测试角度

| 字段 | 内容 |
| --- | --- |
| 主题编号 | T1（测试线第一主题，roadmap 全清后的体检增强轮） |
| 分支 | `feature/t1-deep-audit`（一主题一分支一 PR） |
| 状态 | 已完成（2026-10-02） |

## 1. 目标

对前后端整体做一次深度测试与体检：补上 2026-09-29 M8 审计未覆盖的 **W0~W4 运行时验证**（当时前端未启动）、复核 M8 修复成色、评估既有测试方案合理性、并新增两块测试角度（后端安全/负面矩阵 + 前端 Vitest 单测）。体检报告与全部发现见 `docs/VERIFICATION.md`（2026-10-02 版）。**业务代码零改动**；改动全部是测试资产、验证脚本与文档。

## 2. 主题内子任务

1. **基线与运行时体检**：316/316 全量回归复跑；启动自检四日志 + Kafka 三消费组证据；黑盒三段（part1 复跑 → part2 修复后 15/15 → **part3 新增 46/46** 覆盖 W1~W3 全部补口端点）；actuator 指标核对。
2. **前端 GUI 黑盒走查（W0~W4 首验）**：browser-use 逐页走查 C 端 8 页 + admin 区 9 页 + 40002 过期跳转；秒杀两步流/签到/发帖/热榜闭环/ES 搜索高亮/对账看板实时一致性等跨功能链路全通。
3. **测试方案评估**：优点 4 条（真实中间件/并发语义真测试/串行纪律/清扫协议）；缺口 G-1~G-8 逐条列影响与处置（G-1 前端 0 测试、G-2 无安全负面测试——本轮补齐；G-3 CI、G-4 覆盖率、G-5 环境隔离——用户选择不做，记录建议）。
4. **后端安全/负面矩阵（5 类 29 用例）**：输入校验负面、AdminGuard 越权矩阵（guard ON 上下文）、上传加固（含路径穿越中性化）、token 生命周期（过期/注销/滑动续期）、payload 安全（SQL/XSS 往返）。
5. **前端 Vitest（32 用例）**：seckillPhase/format/authStore/请求拦截器（Result 解包+40002 双跳转+token 注入）/页面冒烟；vitest+RTL+jsdom 框架接入 package.json 与 vite.config.ts。
6. **验证脚本修复**：part1/part2 券查询表名错（lk_seckill_voucher→lk_voucher）、part2 还原顺序与 userId 契约、part2 改名段重写为自建店铺生命周期、cleanup ES 索引名（lk_posts→post）、part3 登录限流退避。
7. **数据卫生**：修复本机种子数据 cp1252 双重编码（10 店铺+10 类型，逐字节还原与 sql 源一致，快照表留底）；shop1 按种子全字段还原；黑盒/GUI 测试数据全清扫复查归零。

## 3. 设计取舍

- **体检与修复合在一个主题**：project-verify 纯审计不修任何东西；但本主题定位是"测试增强"（用户新增测试的诉求），验证资产（脚本/测试）的修复属于交付本身；**业务代码与业务文档的发现只记录不改**（F-1 文档修复属后续主题，见 VERIFICATION §10）。
- **page=abc 落 500 用钉死现状的测试记录**：断言当前 500+40000 并在注释与报告中标注"修复后应改断言 40001"——比写一个必然红的"理想断言"更可交付（套件保持绿），行为变更时测试会主动报警。
- **request.ts 拦截器测试用自定义 axios adapter**：实例在模块导入时快照 defaults，必须在 import 前装 adapter——采用动态 `await import('../request')`；mock antd message 避免真实 DOM 提示。AGENTS.md「不维护 mock」指页面开发不 mock 后端 API，单测 mock 网络层是测试必要手段，在此声明口径。
- **前端 E2E（Playwright）与 CI/覆盖率不做（用户选择）**：E2E 依赖重、维护成本高，本轮以 browser-use 人工走查 + 单测/集成测补证据；CI 留作建议项。
- **part2 改名测试改为自建店铺**：原版直接改种子店铺 1 并"还原"，但还原值经 mysql 客户端 latin1 通道读中文必坏（连环污染实录：address=x/北京坐标/名字问号）；自建→改→验→删既测同样的缓存失效语义，还顺带覆盖 bloom-after-insert，且零污染。
- **GUI 测试数据手清**：GUI 用户/帖子用 [gui-test] 前缀不在 cleanup 覆盖范围，单独清理；不扩 cleanup 前缀（一次性数据不值得扩协议）。

## 4. 产出物

| 文件 | 说明 |
| --- | --- |
| `docs/VERIFICATION.md` | T1 深度体检报告（2026-10-02 版，含测试方案评估与 F-1~F-16） |
| `localink-server/src/test/java/com/localink/web/{InputValidationNegative,AdminGuardMatrix,UploadHardening,PayloadSafety}IntegrationTest.java` | 后端安全/负面矩阵 4 类 |
| `localink-server/src/test/java/com/localink/user/TokenLifecycleIntegrationTest.java` | 会话生命周期 4 用例 |
| `localink-web/vite.config.ts`（改）、`src/test/setup.ts` | Vitest 框架接入 |
| `localink-web/src/{utils,stores,api,pages}/__tests__/*.test.ts(x)` | 前端单测 6 文件 32 用例 |
| `localink-web/package.json`（改） | test 脚本 + vitest/jsdom/@testing-library devDeps |
| `scripts/verify/api-blackbox-part3.sh` | 新增 W 波次黑盒 46 断言 |
| `scripts/verify/{api-blackbox.sh,api-blackbox-part2.sh,verify-cleanup.sh}`（改） | 表名/契约/还原顺序/ES 索引名修复 |
| `docs/roadmap.md` / `README.md`（改） | T1 行勾选 + 测试徽章更新 |

## 5. 验证记录（2026-10-02 本机实测）

**基线**：`.\mvnw.cmd clean test` 串行独占 → **316/316 BUILD SUCCESS（6:52）**，分模块与 README 徽章逐位一致（server 216 含 2 skipped 月边界守卫）。前端 `npm run build` 通过。

**黑盒**：part1 29/15（15 失败逐条定性为脚本陈旧，后端行为正确）；part2 修复后 **15/15**；part3 **46/46**（含手动关单→DB/Redis 双端库存回补+幂等 false、Feed 游标两页无重复、匿名越权矩阵）。

**GUI（17 页）**：C 端 8 页全过（秒杀两步流成功跳订单、签到/发帖/热榜闭环、ES 高亮、订单 17 位 id 字符串化无精度丢失）；admin 9 页全过（对账看板实时显示 GUI 秒杀订单「一致」——全链路铁证）；40002 跳转 ✓。截图留档。

**M8 修复复核**：A-5 notice:sent 键 TTL ~20h ✓；A-7 guard ON 矩阵 9 用例 ✓；A-2 关单幂等回补 ✓；D-14 字符串化 ✓。

**新增测试**：后端 5 类 29 用例 + 前端 32 用例纳入全量回归后 **345/345 BUILD SUCCESS**（2 skipped 同前）。前端 `npm test` 10.8s 32/32。

**数据**：verify-cleanup 复查残留全 0；种子数据还原后与 `sql/localink.sql` 逐字一致（HEX 比对）；GUI 测试数据手清。

## 6. 学习清单

**核心知识点**：
1. **测试金字塔的"作品集最优解"**：真实中间件集成测 > mock 单测（对简历项目），但安全/负面矩阵与前端测试是被普遍忽略的互补面——本轮 61 个新用例都在补"证明系统在坏输入/坏身份下仍然正确"。
2. **字符集三重坑**：mysql 客户端导入缺 `--default-character-set`（cp1252 双重编码）、docker exec 读值经 latin1 通道（还原值再写入即污染）、终端 GBK 显示（假乱码）——HEX() 是唯一可信判据。
3. **黑盒脚本的静默失效模式**：`2>/dev/null` 吞掉 SQL 错误 → 查询恒空 → 断言连环假失败；断言写法（字面量 "nonzero" 比较）永假——脚本身也需要"对脚本的测试"。
4. **钉死现状测试（characterization test）**：对已知缺陷先写"断言当前行为+标注待改"的测试，修复时测试转红逼你更新——比红套件更可交付。

**面试必问**：
- "你的项目怎么做测试保障？"——316 后端集成（真实中间件）+ 61 安全/负面/前端用例 + 三段黑盒脚本 + GUI 走查；讲清分层与各自守什么。
- "遇到过什么测试本身的坑？"——脚本与 schema 脱节静默假失败、axios 实例快照 defaults 导致 mock 失效、本地限流回环桶让连跑脚本互相干扰。
- "如果给你加 CI？"——前端全量可跑，后端集成测需中间件：Testcontainers 或 services 容器化，编译级先行。

## 7. 下一步

体检结论（VERIFICATION §10）：F-1 种子导入命令补 charset 参数（P1 文档修复）→ F-2/F-3 异常 handler 与分页钳制（钉死测试已就位）→ CI/覆盖率 → 前端空值保护。`_t1_*` 快照表确认数据无误后可删。
