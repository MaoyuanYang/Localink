# 主题任务卡：W0 工程骨架 + 账户商户闭环（Web 前端线开工）

| 字段 | 内容 |
| --- | --- |
| 主题编号 | W0（Web 前端线首主题） |
| 分支 | `feature/w0-skeleton`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-30） |

## 1. 目标

按 roadmap W0 原文落地 `localink-web/` 工程骨架（Vite + React 18 + TS + AntD 5 + Zustand + React Router 6 + Axios：路由 / Axios 封装 / token 管理 / dev proxy → :8086），并交付 C 端第一批页面：登录页、商户列表、商户详情（含普通券领取），全部真实对接 M1 已就绪 API，不维护 mock（对应 PRD 4.7 中 F-WEB-C01~C04 子集）。范围不含秒杀（W1）、后台（W2）、社区（W3）。

另含一个后端小改：dev-only 验证码回查接口 `GET /api/sms/code/dev`（默认关闭的开关控制）。成因：审计 B-18 后验证码不落日志、只能从 Redis 取，登录页演示/联调需要一个顺滑的取码路径；用户已确认采用后端加开关接口的口径。

前置：M1（已完成）。

## 2. 主题内子任务

1. **Vite 工程骨架**：`localink-web/`（非 Maven 模块），vite.config.ts 配 dev proxy `/api` → `http://localhost:8086`（无 rewrite），工程内自带 .gitignore（node_modules/dist）。
2. **Axios 封装 `api/request.ts`**：请求拦截器注入 `Authorization`（裸 token 无 Bearer）；响应拦截器解包 Result（code=0 取 data；非 0 toast 后端 message 并 reject；40002 清 authStore 跳 /login）。
3. **token 管理 `stores/authStore.ts`**：Zustand + persist（localStorage）存 token 与 UserDTO；登录成功后拉 `/api/user/me` 回填用户。
4. **类型层 `types/`**：手写对齐 localink-api-model：Result、UserDTO、ShopVO、ShopTypeVO、VoucherVO、Page\<T\>（MyBatis-Plus Page：records/total/size/current/pages）。
5. **C 端布局 + 三页面**：布局（顶部导航 + 登录态/退出）；`/login`（发码 60s 倒计时 + dev 自动取码降级提示）；`/`（类型筛选 + 分页商户列表）；`/shop/:id`（详情 + 券列表 + 领取返回 orderId）。
6. **后端 dev 取码接口**：`DevSmsController`（@ConditionalOnProperty 开关 `localink.dev.sms-code-query.enabled`，默认 false），开关关闭时 Bean 不装配 → 404；deploy.md 注明演示开启方式。

## 3. 设计取舍

- **dev proxy 无 rewrite + axios baseURL 留空 = 路径与后端 1:1**：api 层直接写全路径 `/api/...`，proxy 原样转发到 8086，避免"baseURL=/api 时调用路径少一段"的心智错位；也不需要后端加任何 CORS 配置。
- **验证码取码走后端开关接口而非前端 mock 或放开日志 = 保留"真实验证码登录"验收口径**：接口默认关闭（暴露即等于任意账号可被登录，安全默认与 admin-guard 同哲学），本地/演示用 `--localink.dev.sms-code-query.enabled=true` 或 application-local.yml 开启；前端 dev 模式自动调、失败降级提示 redis-cli 命令，生产构建不引入该逻辑。
- **商户图片占位兜底 = 图床资产后置**：种子数据 images 是 `/images/shop/x.jpg` 逗号分隔相对路径且后端不映射；W0 前端 `split(",")` 后正常引相对路径 + onError 落占位图，将来图放 `public/images/shop/` 即自动生效，图源决策留给 W4。
- **不配 ESLint、不加单测 = 演示定位、最小迭代**：web-frontend.md 边界声明"前端是演示+冒烟层，权威验证走 Apifox"，W0 验证=真实联调冒烟走查（记录于 §6）；工程规范约束靠目录约定与 review。
- **40002 全局拦截清态跳登录 = 会话失效收敛于一点**：token TTL 30 分钟滑动续期，过期后任意接口 40002，由响应拦截器统一处理，页面代码不各自判登录态。
- **错误码文案直接用后端 message = 前端不硬编码错误码**：减少双端文案漂移（web-frontend.md §5 约定）。
- **后端开关接口直接读 Redis 而不扩展 SmsService = dev 逻辑不进生产代码路径**：Controller 注入 RedisCache + KeyBuilder 读 `sms:code:{phone}`，SmsService 保持纯净；该 Controller 整体由开关条件装配，关闭时连路由都不存在。

## 4. 接口 / 配置

W0 前端消费的后端接口（均已就绪）：

| 接口 | 方法与路径 | 说明 |
| --- | --- | --- |
| 发验证码 | POST `/api/sms/code`，body `{"phone"}` | IP 限流 10/min；120s 内重发拒 20001 |
| dev 回查验证码 | GET `/api/sms/code/dev?phone=` | **本主题新增**，开关默认 false；未发码/已过期拒 20003 |
| 登录（自动注册） | POST `/api/user/login`，body `{"phone","code"}` | data = 32 位 token 字符串 |
| 当前用户 | GET `/api/user/me` | UserDTO：id/phone/nickName/icon/level |
| 退出 | DELETE `/api/user/logout` | 需 Authorization header |
| 商户分页 | GET `/api/shop/page?typeId=&page=&size=` | Page\<ShopVO\>，size 后端钳 50 |
| 商户详情 | GET `/api/shop/{id}` | ShopVO（images 逗号分隔相对路径） |
| 商户类型 | GET `/api/shop-type/list` | List\<ShopTypeVO\> |
| 店铺券列表 | GET `/api/voucher/list?shopId=` | List\<VoucherVO\>，仅上架 |
| 领普通券 | POST `/api/voucher/{id}/claim` | 需登录；data = orderId 字符串 |

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `localink.dev.sms-code-query.enabled` | false | 开启后暴露 GET /api/sms/code/dev（回查验证码）；本地联调/演示开启，对外部署必须保持 false |

## 5. 产出物

| 文件 | 说明 |
| --- | --- |
| `localink-server/.../controller/DevSmsController.java` | dev-only 取码接口（@ConditionalOnProperty 开关） |
| `localink-server/.../resources/application.yml` | dev.sms-code-query 开关项（默认 false） |
| `localink-web/`（Vite 工程） | package.json / vite.config.ts（proxy）/ tsconfig / .gitignore |
| `src/api/request.ts` | axios 实例：token 注入、Result 解包、40002 全局跳登录 |
| `src/api/{user,shop,voucher}.ts` | 按域分文件的接口层 |
| `src/stores/authStore.ts` | Zustand persist：token + user |
| `src/types/api.ts` | Result/UserDTO/ShopVO/ShopTypeVO/VoucherVO/Page 手写类型 |
| `src/router/index.tsx` + `src/components/AppLayout.tsx` | 路由表 + C 端布局（登录态导航） |
| `src/pages/{LoginPage,ShopListPage,ShopDetailPage}.tsx` | 三页面 |
| 测试 `DevSmsQuery*Test.java` ×2 | 开关关 → 404；开关开 → 回查 6 位码 / 未发码 20003（+基线回归） |
| `docs/roadmap.md` / `README.md` / `docs/web-frontend.md` / `docs/deploy.md` | W0 勾选、前端状态更新、落地注记、开关说明 |

## 6. 验证记录（2026-09-30 本机实测）

**测试基线 296 → 302/302**（分模块 cache 52 / lock 9 / idempotent 6 / ratelimit 16 / mq 4 / delay 3 / id 9 / search 1 / server 196→202；新增 DevSmsQueryDisabledTest 1 + DevSmsQueryEnabledTest 5），`mvnw clean test` 全 reactor 串行 BUILD SUCCESS（13 模块）。

**前端构建**：`tsc && vite build` 首跑通过（27s，975KB chunk 为 AntD 全量引入，W4 再议 code-split）。

**联调冒烟（docker 中间件 + jar 8086 带 dev 开关 + vite 5173，接口走 vite 代理）**，清单全部通过：

1. ✅ 发码 → dev 接口回查（222736/394910/889850 多轮）→ 登录成功（32 位 token）→ me 返回自动注册用户
2. ✅ GUI 登录页：发码按钮 60s 倒计时 + dev 接口自动回填验证码（889850）→ 登录 → 跳首页 → 导航栏显示昵称"用户2105167981855264770"
3. ✅ 商户列表真实渲染（53 家、9/页、分页器、类型筛选下拉、评分/月售/人均格式化）
4. ✅ 商户详情字段全渲染（地址/营业时间/坐标/评分四标签）+ 图集占位兜底
5. ✅ 券列表 type=1 过滤生效（商户 1 的 9 张历史秒杀券被滤除，仅显示普通券）；GUI 领取成功："领取成功，订单号 2105168054764851202"
6. ✅ 未登录领取 → 40002 → 前端清态自动跳 /login（GUI 验证）；退出 → localStorage 清空（`{"state":{"token":null,"user":null}}`）→ 跳 /login；退出后带旧 token 再领 → 40002（curl 验证）

**冒烟数据已清扫**（M7-A 教训执行）：2 个冒烟用户、1 张 W0 冒烟券、2 笔冒烟订单（ds_0.lk_voucher_order_0 与 ds_1.lk_voucher_order_1 各 1）按物理表 DELETE 后四项复查归零；Redis 无 sms:code/login 残留。同库另有 2 笔 id=98xxx 短 id 订单为今日全量测试产物，非冒烟数据未动。

**排障记录**：

1. **全量首跑 DevSmsQueryEnabledTest 上下文加载失败（Too many connections）**：新增 `@SpringBootTest(properties=...)` 独立上下文把全量测试的 MySQL 151 连接峰值挤爆（与 M6-D/M6-F 同因第三次复现；@DirtiesContext 只能类后回收、防不住"加载时"峰值）。修复：Enabled 测试去 @SpringBootTest——Controller 行为改 standalone MockMvc + mock 依赖（3 用例），开关装配语义改轻量 ApplicationContextRunner（2 用例；注意 `withBean` 直注册不评估 @Conditional，须 `withUserConfiguration`）。Disabled 测试保留默认共享上下文（404 端到端）不增峰值。
2. **surefire `-Dtest` 用 `+` 分隔多类静默不执行**：surefire 3.2.5 对 `A*+B` 模式不匹配且 BUILD SUCCESS 无提示；改逗号分隔 `-Dtest=A,B` 才生效。
3. **Git Bash curl 中文 JSON body 报 40001 请求体格式错误**：curl -d 内联中文按本地编码发送，Jackson UTF-8 解析失败；改 `--data-binary @utf8文件` + `charset=UTF-8` 解决。
4. **curl 提取 token 的正则从错误响应里匹配出垃圾值**：登录 40001 时 `grep -o '[a-f0-9]*'` 仍能从 message 里凑出字母数字串，导致后续请求 40002 假象；改精确模式 `"data":"([a-f0-9]{32})"` + 长度校验。
5. **联调发现券列表混入秒杀券**：`GET /api/voucher/list` 返回商户全部上架券（含 type=2 秒杀券，历史测试数据），claim 仅支持 type=1；前端 ShopDetailPage 按 `type===1` 过滤，秒杀券留 W1 专用页面。
6. **商户名"[verify]renamed-p2"**：shop 1 名称是 M8 审计测试残留（非本主题产物），冒烟如实记录；演示前可手工改名，不影响功能。

## 7. 学习清单

**核心知识点**

1. **dev proxy 无 rewrite**：Vite dev server 把 `/api` 前缀请求原样转发到 8086，浏览器同源无 CORS；对比"后端配 CORS"方案——代理方案零后端侵入、且和生产 nginx 反代结构同构（W4 演进伏笔）。
2. **axios 拦截器双层职责**：请求层注入凭证（从 store 读 token，不逐调用传）；响应层做统一解包与会话失效收敛——业务代码拿到的是"已解包 data 或已 reject 的 Promise"。
3. **Zustand persist 中间件**：token 持久化 localStorage 与内存态同步；对比 Redux Toolkit 的样板成本。
4. **条件装配的安全默认**：@ConditionalOnProperty 默认 false 让危险端点"默认不存在"（连路由都没有），比"存在但校验拒绝"更强；与 admin-guard 开关同一哲学。
5. **Result 协议错误码段**：0 成功 / 1xxxx 券 / 2xxxx 用户 / 3xxxx 社区 / 4xxxx 框架——前端只需要认识 40002（会话）这一个码，其余码文案直接展示后端 message。

**面试必问题**

1. "前端怎么管理登录态？"——token 存 Zustand persist（localStorage），请求拦截器注入 Authorization 裸值；30 分钟滑动续期由后端拦截器完成，前端 40002 全局清态跳登录。追问"为什么不用 cookie/session"→ 部署结构前后端分离+nginx 反代演进预留，token 无状态便于后端水平扩展（与 M1.5 选型一致）。
2. "开发环境怎么解决跨域？"——不解决，绕开：Vite dev proxy 让浏览器同源访问 5173、代理转发 8086；生产 W4 用 nginx 反代同构替换。追问"为什么不后端配 CORS"→ 可行但每后端都要配、且凭证头要白名单，代理方案零侵入。
3. "验证码接口为什么做成开关？"——模拟短信不落日志（安全审计结论），取码接口本身等于"任意账号可登录"的后门，必须默认不存在；条件装配让关闭时路由都不注册（404），强于运行时校验。

## 8. 下一步

W1 秒杀演示：秒杀券详情（倒计时 + 抢购按钮状态机 + 两步流令牌申请）+ 我的订单（前置 M3 已就绪；订单查询接口缺口届时评估是否随 W1 后端补齐）。
