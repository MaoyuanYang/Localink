# 迭代任务卡：M0.2 架构设计文档

| 字段 | 内容 |
|---|---|
| 迭代编号 | M0.2 |
| 分支 | `feature/m0-2-architecture` |
| 状态 | 已完成（2026-07-24 确认，roadmap 已勾选） |

---

## 1. 目标

产出 `docs/architecture.md`：确定 Maven 模块划分、技术选型、核心链路目标态、横切约定（Key/Topic/错误码/序列化）与数据架构演进路径，作为 M0.4 骨架搭建与后续所有迭代的图纸。

## 2. 设计取舍

- **为什么单体 + starter 组件化，而不是微服务**：面试考察组件深度而非服务数量；starter 形态本身就是"框架化能力"的证明（每个组件可独立复用、自动装配），且本地调试链路短
- **为什么 starter 命名不用 hmdp-plus 的 `-framework`**：差异化 + 更符合 Spring 生态习惯（`-starter`）；包名 `com.localink`，端口 8086、Key 前缀 `lk:`、Topic 前缀 `lk-` 均为自有风格
- **为什么把布隆过滤器并入 cache-starter 而非独立模块**：布隆是缓存防穿透链路的一环，与 Key 治理、空值缓存强耦合，放一起内聚性更高
- **为什么 Kafka 而非 RabbitMQ**：大厂主流 + 高吞吐 + `acks=all` 与幂等生产者正好支撑秒杀可靠性叙事；本地用 KRaft 模式免 ZK
- **为什么演进路径保留旧代实现**：V1/V2/V3 同存是压测对比素材（M7 报告需要），也是面试"你是怎么做优化决策"的实证

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `docs/architecture.md` | 架构设计 v1.0（模块表/选型表/链路图/横切约定/演进路径/风险） |

## 4. 验证记录

- [x] 12 个模块职责互不重叠，依赖规则单向无环
- [x] 每个 PRD 功能编号都能落到具体模块（如 F-TRD-03→cache+server 的 lua 包，F-COM-04→search-starter）
- [x] 端口/Key前缀/Topic前缀与 hmdp-plus 完全区隔

## 5. 学习清单

**核心知识点**
1. SpringBoot starter 的自动装配原理：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（SpringBoot 3 新位置，替代旧 `spring.factories`）
2. 模块化设计的核心规则：单向依赖、业务不依赖业务、框架不知晓业务
3. 技术选型表的三段式写法：选型 / 理由 / 备选及否决原因——面试"为什么用 Kafka 不用 RabbitMQ"即源于此
4. Redis Cluster hash tag（`{voucherId}`）：多 key Lua 脚本必须同槽位，否则 CROSSSLOT 错误

**面试必问题**
1. "你的项目架构是什么样的？"——用第 1 节分层图 + 第 2 节模块表回答
2. "为什么不用微服务？"——第 2 节设计取舍第 1 条
3. "Kafka 和 RabbitMQ 怎么选？"——第 3 节选型表
4. "Lua 脚本里多个 key 有什么讲究？"——hash tag 同槽位

## 6. 下一步

M0.3 中间件环境安装：MySQL 8 / Redis / Kafka（KRaft）Windows 安装指引 + 连通性验证。
