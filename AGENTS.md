# AGENTS.md — Localink 项目开发与协作规范

本文件是 Localink 项目的**最高执行规范**，所有开发参与者（包括 AI 协作者）必须严格遵守。任何迭代开始前先阅读本文件。

---

## 1. 项目概述

- **项目名称**：Localink —— 本地生活社区平台
- **项目目标**：Java 后端求职作品。完整复现"高并发优惠券秒杀 + 热点查询"企业级能力，并扩展 UGC 社区（帖子/Feed/搜索/热榜/审核）形成差异化
- **Web 端**：`localink-web/`（React+TS 演示界面，C 端 + 运营后台双区，M1 后启动，设计见 `docs/web-frontend.md`）
- **参考项目**：`../hmdp-plus`（开源项目，仅作设计参考）
- **仓库**：https://github.com/MaoyuanYang/Localink.git

## 2. 铁律（不可违反）

1. **参考设计、重写实现**：禁止从 hmdp-plus 复制粘贴源码。包结构、命名、实现细节均为 Localink 自有风格；参考仅限于架构思路与方案选型
2. **演进式开发**：每个技术点按"先暴露问题 → 再解决问题"的顺序迭代（例：秒杀先做纯 DB 版压测暴露超卖，再逐代加乐观锁/Redis/Lua/Kafka），保证每个组件的"为什么存在"可解释
3. **最小迭代**：每次只完成一个迭代点，做到可运行、可验证，经用户确认后才进入下一个
4. **不直接提交 main**：一切改动走 feature 分支 + PR

## 3. 技术栈

| 类别 | 选型 | 版本 |
|---|---|---|
| 语言/框架 | Java（编译目标 17）+ Spring Boot | 3.5.4 |
| 构建 | Maven 多模块（含 Maven Wrapper） | 3.9+ |
| ORM | MyBatis-Plus | 3.5.7 |
| 数据库 | MySQL | 8.x |
| 缓存 | Redis + Redisson + Caffeine | 7.x / 3.52.x |
| 消息队列 | Kafka | 3.x |
| 分库分表 | ShardingSphere-JDBC | 5.3.2 |
| 搜索 | Elasticsearch（M6 引入） | 8.x |
| 工具 | Lombok / Hutool / fastjson2 / Knife4j | — |
| 根包名 | `com.localink` | — |
| 前端（localink-web/） | React + TypeScript + Vite + Ant Design + Zustand + Axios | 18 / 6 / 5.x |

## 4. 迭代流程（六步闭环，每个迭代必须走完）

1. **任务卡**：在 `docs/iterations/mX-Y-名称.md` 写明：目标、设计取舍（为什么这么做、备选方案是什么）、涉及的表/接口/Redis Key
2. **实现**：最小可运行代码
3. **验证**：接口自测或单测，验证过程与结果记录到任务卡"验证记录"一节
4. **提交**：feature 分支提交 → 推送 → 用户 PR 自审合入 main
5. **学习清单**：任务卡末尾给出"核心知识点 + 面试必问题"
6. **用户确认**：用户学习并确认后，勾选 `docs/roadmap.md` 对应项，开始下一迭代

## 5. Git 规范

- **分支**：`main`（主干，受保护）/ `feature/mX-Y-描述`（迭代分支，如 `feature/m3-6-seckill-lua`）
- **提交信息**：Conventional Commits 格式 `type(scope): subject`
  - type：`feat` 新功能 / `fix` 修复 / `docs` 文档 / `refactor` 重构 / `test` 测试 / `chore` 构建杂项 / `perf` 性能
  - 示例：`feat(seckill): 秒杀下单V1-纯DB实现`
- **合并**：用户审查 PR 后合入 main，随后删除 feature 分支
- **禁止**：提交密钥、本地环境配置（`application-local.yml` 等）、IDE 文件

## 6. 代码规范

- 根包 `com.localink`，按模块分包：`controller / service / mapper / entity / dto / config / framework.*`
- **解释性内容写入迭代任务卡，不写进代码注释**；仅公共 API 保留必要 Javadoc
- 统一返回体 `Result`，错误码统一走枚举 `BaseCode`，业务异常统一抛 `LocalinkException`，全局异常处理器兜底
- Redis Key 一律通过 Key 治理组件生成（统一环境前缀），禁止散落硬编码
- 配置外置 `application.yml`，环境差异走 `application-{profile}.yml`
- 每个 Controller 方法入参用 DTO + jakarta.validation 校验

**前端（localink-web/，W0 起生效）**
- 单工程双区：`/` C 端、`/admin` 运营后台，共享 `api/types/utils`；目录约定与对接规范见 `docs/web-frontend.md`
- 不是 Maven 模块，不参与 `mvnw package`；独立 npm 链路
- 类型定义手写对齐 `localink-api-model` 的 DTO/VO；统一走 axios 拦截器注入 token、解包 `Result`
- 不超前开发（后端 API 未就绪的页面不动工）、不维护 mock

## 7. 文档结构

```
docs/
├── prd.md              # 需求文档（M0.1）
├── architecture.md     # 架构设计（M0.2）
├── database.md         # 数据库设计（M1.1）
├── roadmap.md          # 迭代路线图（勾选跟踪）
├── middleware-setup.md # 中间件安装指引（M0.3）
├── web-frontend.md     # Web 前端设计（M0.7）
└── iterations/         # 每迭代一张任务卡
    └── mX-Y-名称.md
```

## 8. 进度跟踪

- `docs/roadmap.md` 是唯一进度源：`- [ ]` 待办 / `- [x]` 已完成（附完成日期）
- 每个迭代 PR 中必须包含 roadmap.md 的勾选更新

## 9. 决策分歧处理

遇到方案选型分歧（如 ES 同步用 MQ 还是 Canal）时：先输出对比分析（方案/优缺点/结论），经用户确认后再动手。

## 10. 环境与常用命令

- JDK 21（编译目标 17）；构建一律使用 Maven Wrapper：`.\mvnw.cmd`
- 中间件：MySQL 8 / Redis / Kafka / ES 的安装与验证见 `docs/middleware-setup.md`（`docker compose up -d mysql redis kafka`）
- 全量构建：`.\mvnw.cmd clean package "-DskipTests"`（PowerShell 中 `-D` 参数必须加引号）
- 启动服务：`java -jar localink-server/target/localink-server-0.0.1-SNAPSHOT.jar`（端口 8086，`GET /ping` 冒烟）
