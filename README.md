# Localink

![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.4-6DB33F?logo=springboot&logoColor=white) ![MySQL](https://img.shields.io/badge/MySQL-8-4479A1?logo=mysql&logoColor=white) ![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white) ![Kafka](https://img.shields.io/badge/Kafka-3.9-231F20?logo=apachekafka&logoColor=white) ![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.15-343741?logo=elasticsearch&logoColor=white) ![Tests](https://img.shields.io/badge/tests-287%2F287-brightgreen) ![Modules](https://img.shields.io/badge/starters-8%20built-blue)

本地生活社区平台 = **商户优惠秒杀**（高并发工程能力）+ **UGC 社区**（业务差异化）。

对标业务形态：大众点评（商户+评价）× 美团限时秒杀 × 小红书社区。项目以"演进式开发"方式构建：每个技术组件都按"先暴露问题 → 再解决问题"的顺序落地，保证每个设计决策可追溯、可解释。

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言/框架 | Java 17（JDK 21 运行）+ Spring Boot 3.5.4 |
| 构建 | Maven 多模块（Maven Wrapper，免本地安装） |
| ORM | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8（M4 起 ShardingSphere-JDBC 5.5.1 分库分表） |
| 缓存 | Redis 7 + Redisson 3.52 + Caffeine（本地缓存 + 布隆过滤器） |
| 消息队列 | Kafka 3.9（KRaft 模式） |
| 搜索 | Elasticsearch 8.15（M6 引入） |
| 中间件环境 | Docker Compose 一键编排 |

## 模块结构

```
localink                      # 父 POM，统一版本治理
├── localink-common           # 统一返回、错误码、异常体系、枚举、工具
├── localink-api-model        # DTO/VO 与参数校验
├── localink-cache-starter    # 缓存框架：RedisCache、Key 治理、本地缓存、布隆过滤器
├── localink-lock-starter     # 分布式锁框架：四种锁类型 + @ServiceLock 注解
├── localink-idempotent-starter  # 幂等框架：三级防护注解
├── localink-ratelimit-starter   # 限流框架：令牌桶/滑动窗口 Lua、秒杀访问令牌
├── localink-mq-starter       # MQ 框架：Kafka 生产者/消费者模板基类
├── localink-delay-starter    # 延迟队列框架：分片 + 线程池消费
├── localink-id-starter       # 全局 ID：雪花算法 + Redis 分配 workId
├── localink-sharding         # 分库分表集成
├── localink-search-starter   # ES 搜索（M6 启用）
└── localink-server           # 唯一可启动业务应用（端口 8086）

localink-web/                 # Web 演示界面（规划中：React+TS，C端+/admin后台，W 线未启动——见 docs/web-frontend.md）
```

## 快速开始

```powershell
# 1. 启动中间件（需要 Docker Desktop；搜索功能另需 --profile es）
docker compose up -d mysql redis kafka

# 2. 构建
.\mvnw.cmd clean package "-DskipTests"

# 3. 启动服务
java -jar localink-server/target/localink-server-0.0.1-SNAPSHOT.jar

# 4. 冒烟验证
curl http://localhost:8086/ping   # -> pong
```

首次部署（含 SQL 双库初始化、启动自检清单、ES 重建、常见问题）见 **[docs/deploy.md](docs/deploy.md)**。

## 核心能力（按迭代逐步落地）

- **高并发秒杀**：令牌前置授权 + 令牌桶限流 → Lua 原子扣减 → Kafka 异步建单 → 幂等/回滚/对账一致性闭环
- **多层缓存**：本地缓存 + Redis + 空值缓存 + 布隆过滤器 + 双重检查重建 + 逻辑过期
- **数据层扩展**：雪花全局 ID、分库分表、订单路由表
- **社区能力**：帖子/点赞/关注、Feed 推挽结合、ES 全文搜索、时间衰减热榜、DFA 敏感词审核
- **运营能力**：开抢预通知（延迟队列）、到券订阅自动发券、店铺每日 Top 买家

## 压测结果（M7-A，2026-09-28 实测）

环境口径：22 逻辑核单机、JMeter 5.6.3 与服务同机、中间件 Docker/WSL2（数字偏保守，如实声明）。

| 场景（最优档） | 基线 | 调优后 | 变化 |
|---|---|---|---|
| 缓存命中 @1000 线程 | 3966 QPS / P99 1186ms | **12310 QPS / P99 172ms** | +210% |
| 未登录拒绝 @300 | 3722 / P99 828 | **5333 / P99 461** | +43% |
| 无效令牌拒绝 @100 | 1549 / P99 95 | **1630 / P99 101** | Redis 往返主导 |
| 成功路径 400 并发 | 正确率 75% | **100%**（800/800） | 拒连/爆池双归零 |

调优三件套：accept-count 100→1000（治突发拒连）、Hikari 池 10→30（ds_0 硬编码透出，治爆池 waiting=122）、Kafka 消费并发 1→3（对齐分区）。**threads.max 400 实验为负优化**（拒绝路径 -64% 调度饥饿）已回退，反例数据保留。复现命令见 [scripts/jmeter/README.md](scripts/jmeter/README.md)，完整报告见 [docs/iterations/m7-load-test.md](docs/iterations/m7-load-test.md)。

## 文档

| 文档 | 说明 |
|---|---|
| [docs/prd.md](docs/prd.md) | 产品需求文档 |
| [docs/architecture.md](docs/architecture.md) | 架构设计（mermaid 总览图/六代演进/性能档案） |
| [docs/deploy.md](docs/deploy.md) | 部署手册（SQL 初始化/构建启动/自检清单/FAQ） |
| [docs/database.md](docs/database.md) | 数据库设计（ER 图/12 张表字段说明） |
| [docs/sharding.md](docs/sharding.md) | 分库分表设计（分片键取舍/5.5.1 升级实录） |
| [docs/roadmap.md](docs/roadmap.md) | 迭代路线图（勾选跟踪） |
| [docs/middleware-setup.md](docs/middleware-setup.md) | 中间件安装与验证指引 |
| [docs/interview-qa-30.md](docs/interview-qa-30.md) | 30 道核心自测题（含答题要点与出处） |
| [docs/web-frontend.md](docs/web-frontend.md) | Web 前端设计（W 线，未启动） |
| [docs/iterations/](docs/iterations/) | 每个迭代的任务卡（设计取舍/验证记录/学习清单） |
| [AGENTS.md](AGENTS.md) | 项目开发与协作最高规范 |

## 开发状态

**M0~M4 已全部收官（2026-08-18 ~ 2026-09-22）**，进度按技术主题跟踪（一主题=一任务卡+一分支+一 PR，规则见 [AGENTS.md](AGENTS.md)）：

- **M0/M1 工程奠基与基础闭环**：多模块骨架、统一返回与异常、短信验证码、登录鉴权、商户与券 CRUD
- **M2 缓存体系**：Redis 工具框架与 Key 治理 → 缓存三防（穿透/雪崩/击穿，A/B 实测）→ 布隆 + Caffeine 多级读防线（读链路最终形态：本地缓存 → 布隆 → Redis 逻辑过期 → DB 四级纵深）
- **M3 秒杀核心链路（五个主题）**：纯 DB 与超卖实录（基线 QPS 88.8/s）→ 分布式锁与一人一单 → Redis+Lua+Kafka 异步秒杀（QPS 74.9 → 168、峰值吸收 847/s、Avg -81%）→ 一致性闭环（幂等/消费可靠性/对账日志 traceId 三层串联）→ 流量防线（令牌桶/滑动窗口限流、@RateLimit 双维度、前置令牌）
- **M4 数据层扩展**：自研雪花（时钟回拨分档）+ Redis 轮转机器位 → ShardingSphere 5.5.1 双库分片（库 user_id%/表 voucher_id%）+ 订单路由表
- **M5 一致性闭环**：对账体系（Redis 流水 vs DB 单向比对、差异补偿复用统一回滚、终态未恢复裁决关单）→ 延迟队列与关单（StringCodec 信封重投、条件关单闸门、超时 15m）→ 通知与运营统计（开抢前 2min 预通知圈名单群发、订阅 popMin 自动发券、Top 买家按日 ZINCRBY）

当前测试基线 **287/287**（分模块 cache 52 / lock 9 / idempotent 6 / ratelimit 16 / mq 4 / delay 3 / id 9 / search 1 / server 187）。**M6 社区扩展六主题全部收官**：A 内容基础、B 互动关系、C Feed 流、D 搜索、E 热点统计、F 风控与特色（DFA 两级审核+BitMap 签到+GEO 附近商户，m2-1 预留的 Redis 结构扩组点全部兑现）。**M7 收尾两主题收官**：M7-A 全链路压测与调优（结果见上方专章）+ M7-B 交付与面试弹药（架构文档 v2.0 / 部署手册 / 30 道自测题）。**后端主线 M0~M7 全部完成**；剩余工作：Web 前端线（W0~W4，独立推进）。

完整进度见 [docs/roadmap.md](docs/roadmap.md)。
