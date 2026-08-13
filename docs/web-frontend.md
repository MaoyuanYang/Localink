# Localink Web 前端设计文档

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-08-13 | 初版（M0.7 产出）；代码在 M1 完成后启动 |

---

## 1. 定位与边界

`localink-web/` 是 Localink 后端能力的**演示载体**：把秒杀、缓存、社区等后端链路以真实页面呈现，服务于面试演示与联调自测。明确边界：

- **不是**生产级产品 UI——不投入视觉设计系统，Ant Design 默认风格为主
- **不是**独立业务——前端不承载业务规则，全部规则以后端 API 为准
- 后端接口的权威验证方式仍是 Apifox；前端是"演示 + 冒烟"层

工程位于仓库顶层 `localink-web/`，**不是 Maven 模块**，不参与 `mvnw package`，有独立的 npm 构建链路。

## 2. 技术选型

| 能力 | 选型 | 理由 | 备选（为什么不用） |
|---|---|---|---|
| 框架 | React 18 + TypeScript | 类型安全对接后端 DTO；面试叙事中与 Vue 参考项目形成"重写"差异 | Vue3（与 hmdp-plus 同栈，易触发复制粘贴嫌疑，且铁律要求重写） |
| 构建 | Vite 6 | 秒级冷启动、dev proxy 开箱即用 | CRA（停止维护）、webpack（配置重） |
| 组件库 | Ant Design 5 | 中后台事实标准，表格/表单/日期组件齐全，后台区零成本 | Element Plus（Vue 专属）、Naive UI（生态较小） |
| 状态管理 | Zustand | 轻量（~1KB），登录态/用户信息两个 store 足够，无样板代码 | Redux Toolkit（样板重，本项目状态复杂度用不上） |
| 路由 | React Router 6 | 官方标准，嵌套路由支撑 /admin 分区 | — |
| HTTP | Axios | 拦截器统一做 token 注入与 Result 解包 | fetch（需手写拦截层） |

## 3. 单工程双区架构

一个 Vite 工程内分两个区，共享 api 层、类型定义、工具：

```
/            → C 端（用户视角：商户/秒杀/社区）
/admin       → 运营后台（AntD ProLayout 风格布局 + 登录守卫）
```

**为什么不做成两个工程**：双工程意味着两套构建配置、两份 axios 封装、类型定义重复维护——演示项目的基建重复没有任何面试收益。单工程内用路由前缀分区，共享层自然复用。

**为什么 C 端也用 AntD 而不是更"C 端风"的组件库**：定位是演示后端能力，不是演示前端视觉；一套组件库降低心智负担。

## 4. 目录结构约定

```
localink-web/
├── index.html
├── package.json
├── vite.config.ts          # dev proxy → http://localhost:8086
├── tsconfig.json
└── src/
    ├── main.tsx
    ├── App.tsx
    ├── router/             # 路由表：c 端路由 + /admin 子路由（守卫）
    ├── api/                # 按后端域分文件：user.ts / shop.ts / voucher.ts / seckill.ts ...
    │   └── request.ts      # axios 实例：baseURL、token 注入、Result 解包、错误码 toast
    ├── types/              # 与 localink-api-model 对齐的 DTO/VO 类型（手写，非生成）
    ├── stores/             # Zustand：authStore（token/用户信息）等
    ├── pages/              # C 端页面
    │   └── ...
    ├── pages/admin/        # 后台页面（布局 + 各管理页）
    ├── components/         # 跨区共享组件
    └── utils/              # 格式化、常量等
```

## 5. 前后端对接约定

| 约定 | 规范 |
|---|---|
| baseURL | 开发环境走 Vite dev proxy（`/api` → `http://localhost:8086`），避免手写 CORS |
| 认证 | token 存 `authStore`（持久化 localStorage），axios 请求拦截器注入请求头；头字段名以 M1.5 双拦截器实现为准 |
| 统一返回 | 响应拦截器解包 `Result`：`code=0` 取 `data`，非 0 按错误码 toast 并 reject |
| 会话失效 | 捕获 2xxxx 未登录错误码 → 清空 authStore → 跳转登录页（C 端）或 /admin/login |
| 分页 | 统一 `page`/`size` 请求参数与 `total`/`records` 响应结构，与后端分页对象对齐 |
| 时间 | 后端统一 `yyyy-MM-dd HH:mm:ss` 字符串，前端倒计时等场景自行 parse |
| 错误码 | 前端不硬编码错误码文案，toast 直接展示后端 `message` |

## 6. 页面清单

页面需求编号与优先级见 PRD 4.7（F-WEB-C01~C10、F-WEB-A01~A07），此处不重复维护。

## 7. 迭代线（与后端里程碑强绑定）

| 迭代 | 内容 | 前置（后端就绪） | 核心验收 |
|---|---|---|---|
| W0 | 工程骨架 + 登录页 + 商户列表/详情 + 普通券领取 | M1 | 真实验证码登录成功；商户/券数据真实渲染 M1 API |
| W1 | 秒杀券详情（倒计时 + 抢购按钮状态机）+ 我的订单 | M3 | 秒杀全链路在页面可操作，压测时按钮态/结果反馈正确 |
| W2 | /admin 骨架 + 商户/券管理 + 订阅提醒 + 订单状态展示 | M5 | 运营可发券配活动；订单超时关闭状态可见 |
| W3 | 社区全套 + 审核队列 + Top买家/对账看板 | M6 | 发帖→审核→Feed→搜索→热榜链路页面闭环 |
| W4 | 打磨 + README + nginx 部署说明 | M7 | 一键起前后端，演示脚本可走查 |

规则：**不超前开发**（后端 API 未就绪的页面不动工）、**不维护 mock**（宁缺毋滥，用 Apifox 顶）、每个 W 迭代走 AGENTS.md 六步闭环。

## 8. 演进规则（铁律适配）

- 前端同样遵守：演进式开发、最小迭代、feature 分支 + PR、Conventional Commits
- 禁止从 `hmdp-plus/hmdp-vue3` 复制代码——技术栈不同（Vue→React），天然全部重写，参考仅限于页面结构与接口对接思路
- 解释性内容写入迭代任务卡，不写进代码注释
- 后端 API 演进导致前端适配时（如 M3 各代秒杀），前端跟随升级而非保留多代——多代对比素材以后端压测为准
