# 主题任务卡：W4 打磨 + README + nginx 部署（前端线收官）

| 字段 | 内容 |
| --- | --- |
| 主题编号 | W4（Web 前端线第五主题/收官） |
| 分支 | `feature/w4-polish`（一主题一分支一 PR） |
| 状态 | 已完成（2026-10-02） |

## 1. 目标

前端线收官：全局加载/空态打磨（探索清点 18 处待打磨点，按性价比做 14 跳 4）、README 前端章（技术栈/快速开始/收官表述/徽章漂移修复）、deploy.md §10 nginx 生产部署节、一键起前后端脚本与演示走查剧本——兑现 web-frontend.md §7 W4 验收"一键起前后端，演示脚本可走查"。**后端零代码改动**（基线 316 复验）。完成后 roadmap 全清。

## 2. 主题内子任务

1. **SearchPage 参数闭包 bug 修复（真 bug，唯一 fix）**：三处 `setState 后 setTimeout(() => run(), 0)` 捕获旧渲染闭包——切排序/点分面/关标签的搜索带旧参数（UI 显示筛选但请求未生效）。
2. **C 端打磨**：PostDetailPage 5 处（主加载/共同关注 Modal Skeleton、评论 loading、点赞/关注/评论按钮 loading 防连点）；CommunityPage 3 处（Feed 首载 Skeleton、Feed 空态引导、热榜 Skeleton）；LoginForm 登录按钮 loading；SeckillDetailPage Empty 统一；SearchPage 初始引导与搜索中 Skeleton；ShopListPage 二次加载指示。
3. **admin 轻量**：三个 Modal confirmLoading；订单监控/订阅页未选活动引导占位；订阅页裸 Spin 统一；类型面板 loading。
4. **一键与演示**：`scripts/dev-all.ps1`（后端 jar+探活+前端 dev+双端清理，参考 smoke.ps1 模式）；`docs/demo-script.md`（10 分钟一条龙演示剧本，每步操作+预期+机制一句话）。
5. **文档收官**：README（技术栈行/前端快速开始/注记更新/收官表述/徽章 296→316）；deploy.md §10 nginx 节（build→dist→server 块：SPA fallback+/api、/upload 反代+生产注意事项）；web-frontend.md（§5 生产口径行+版本注记）。

## 3. 设计取舍

- **跳过 4 项（记录）**：admin 六页自定义空态文案（AntD zhCN 默认"暂无数据"已可用，纯文案打磨无质变）；热榜深加工；通知中心（后端 10 条 JSON 串接口太薄，演进项）；用户主页/粉丝列表（W3 已确认不做，后端零接口）。
- **闭包修复用函数参数而非 useEffect 依赖**：run 接受 overrides（sort/shopId/searchAfter），调用点把"下一个值"显式传入——比 useEffect 监听 state 变化自动搜索更可控（避免输入框每敲一字触发搜索）。
- **一键脚本用 PowerShell**：与 scripts/smoke.ps1 同构（Start-Process+探活+taskkill /T 进程树清理），Windows 环境主战场；bash 版不做（Git Bash 可直接调 powershell -File）。
- **nginx 节给完整可复制配置块**：SPA fallback（createBrowserRouter 深路径 /shop/:id 等刷新需要）+/api、/upload 反代（与 dev proxy 完全同构，前端零改动）+ gzip 可选段；生产注意事项三条（admin-guard 必开、dev 取码开关必关、upload 目录持久化）。
- **演示剧本是面试演示载体**：每步配"背后机制一句话"（如秒杀两步流配"令牌 30s 一次性把洪峰拆两段可限流"）——演示与叙事合一，任务卡学习清单不重复展开。

## 4. 产出物

| 文件 | 说明 |
| --- | --- |
| `localink-web/src/pages/SearchPage.tsx`（改） | 闭包 bug 修复+初始引导+搜索中 Skeleton |
| `localink-web/src/pages/PostDetailPage.tsx`（改） | 5 处加载/空态/防连点 |
| `localink-web/src/pages/CommunityPage.tsx`（改） | Feed Skeleton+空态、热榜 Skeleton |
| `localink-web/src/{components/LoginForm.tsx, pages/SeckillDetailPage.tsx, pages/ShopListPage.tsx}`（改） | 登录 loading/Empty 统一/二次加载 |
| `localink-web/src/pages/admin/{AdminShopsPage,AdminVouchersPage,AdminOrdersPage,AdminSubscribePage}.tsx`（改） | confirmLoading/引导占位/风格统一 |
| `scripts/dev-all.ps1` | 一键起前后端（双端探活+进程树清理） |
| `docs/demo-script.md` | 10 分钟演示走查剧本 |
| `README.md` / `docs/deploy.md` / `docs/web-frontend.md` / `docs/roadmap.md` | 收官文档四件 |

## 5. 验证记录（2026-10-02 本机实测）

**后端零改动复验**：全量 **316/316** BUILD SUCCESS（2 skipped 为月初 Assumptions 保护，预期）。前端 `tsc && vite build` 通过（26s）。

**dev-all.ps1 一键脚本验收（即 web-frontend.md §7 W4 验收）**：`powershell -File scripts/dev-all.ps1 -DevSms` → 自动构建缺失 jar → 后端探活 [OK] → 前端探活 [OK] → 横幅输出双区地址/剧本路径/日志目录/停止方式；curl 双确认（pong + vite 200）。

**GUI 抽样冒烟**（打磨点核心验证）：

1. ✅ **SearchPage 闭包修复（可观察行为差异）**：造两帖（一帖关联商户 1、一帖无商户）同含关键词 → 搜索 2 条 → 点 facet 商户 → **只剩 1 条"带商户帖"**（shopId 过滤生效——修复前闭包捕获旧参数会仍显示 2 条）；切"按时间"排序触发新请求无错
2. ✅ 搜索初始引导空态："输入关键词开始搜索（标题/正文全文检索，结果高亮）"
3. ✅ Feed 空态引导："关注的人还没有动态——去逛逛商户或发布第一篇帖子"（新账号无关注实测）
4. ✅ admin 订单监控/订阅页未选活动引导占位（"选择商户与秒杀活动后展示…"）
5. ✅ 端口预检两次实战拦截（5173 僵尸 vite、8086 残留后端，脚本拒绝启动并给出 PID——按提示清理后正常）

冒烟数据已清扫（两帖+评论点赞级联+用户+ES 文档+Redis key 复查归零）。

**排障记录**：

1. **dev-all.ps1 无 BOM 被 PowerShell 5.1 按 GBK 解析**：中文提示全部乱码（"灏辩华"）且字符串语法炸（数组索引错误）——Write 工具默认 UTF-8 无 BOM，Windows PowerShell 5.1 对无 BOM 文件按 ANSI 读。修复：补 UTF-8 BOM（utf-8-sig）。新写 .ps1 的标准动作。
2. **Start-Process npm 报"%1 不是有效的 Win32 应用程序"**：npm 在 Windows 是 .cmd 批处理，Start-Process 不能直接跑 shell script——改 `npm.cmd`。
3. **脚本清理链路的小缺口**：前端启动失败分支 exit 1 后 8086 后端未被 finally 完全清掉（Register-EngineEvent 在脚本异常退出时不保证执行）——端口预检兜底（下次运行拦截并提示 PID）。取舍记录：不做完美的信号处理，预检+提示已够演示口径。
4. **Git Bash 传 PowerShell 路径必须正斜杠**：`scripts\dev-all.ps1` 反斜杠被 bash 吞成 `scriptsdev-all.ps1`。

## 6. 学习清单

**核心知识点**

1. **setState 的异步性与闭包陷阱**：React 事件里 setState 后同步读 state 拿到旧值；`setTimeout(() => run(), 0)` 里的 run 是旧渲染的闭包——修复要么把新值显式传参，要么用 ref 存最新值。这是 React 面试高频题的实战版。
2. **加载/空/错误三态是列表页的完整状态机**：Skeleton（首载）→ 内容 → Empty（空态）→ Spin（增量加载），防连点 loading 是写操作的第零态——W4 的本质是把三态机补全到全部页面。
3. **SPA 部署的 nginx 三件套**：静态 root+try_files fallback（深路径刷新 404 问题）、API 反代（同源无 CORS）、上传目录反代——与 dev proxy 同构意味着前端代码零改动切换环境。
4. **演示即叙事**：demo-script 每步"操作+预期+机制一句话"——面试演示的目的不是炫功能，是给每个后端机制一个可见的锚点。

**面试必问题**

1. "React 里 setState 之后马上用它为什么会是旧值？怎么解决？"——setState 是调度不是同步赋值，当前作用域的闭包绑定旧渲染；解法：函数参数显式传、useRef 存最新值、或 useEffect 依赖驱动。本项目 SearchPage 切排序 bug 即此。
2. "前端部署到 nginx 要注意什么？"——SPA 路由 fallback（try_files 到 index.html，否则刷新 /shop/1 就 404）、API/上传反代保持同源（无 CORS 配置）、静态资源缓存策略（带 hash 的 assets 可长缓存）。

## 7. 下一步

前端线收官，roadmap 全清——后续可选：W 线审计（project-verify 前端五维）、演示彩排（按 demo-script 走一遍）、或回到面试准备主线。
