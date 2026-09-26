# Localink

本地生活社区平台 = **商户优惠秒杀**（高并发工程能力）+ **UGC 社区**（业务差异化）。

对标业务形态：大众点评（商户+评价）× 美团限时秒杀 × 小红书社区。项目以"演进式开发"方式构建：每个技术组件都按"先暴露问题 → 再解决问题"的顺序落地，保证每个设计决策可追溯、可解释。

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言/框架 | Java 17（JDK 21 运行）+ Spring Boot 3.5.4 |
| 构建 | Maven 多模块（Maven Wrapper，免本地安装） |
| ORM | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8（M4 起 ShardingSphere-JDBC 5.3.2 分库分表） |
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

localink-web/                 # Web 演示界面（React+TS，C端+/admin后台，非 Maven 模块，M1 后启动）
```

## 快速开始

```powershell
# 1. 启动中间件（需要 Docker Desktop）
docker compose up -d mysql redis kafka

# 2. 构建
.\mvnw.cmd clean package "-DskipTests"

# 3. 启动服务
java -jar localink-server/target/localink-server-0.0.1-SNAPSHOT.jar

# 4. 冒烟验证
curl http://localhost:8086/ping   # -> pong
```

## 核心能力（按迭代逐步落地）

- **高并发秒杀**：令牌前置授权 + 令牌桶限流 → Lua 原子扣减 → Kafka 异步建单 → 幂等/回滚/对账一致性闭环
- **多层缓存**：本地缓存 + Redis + 空值缓存 + 布隆过滤器 + 双重检查重建 + 逻辑过期
- **数据层扩展**：雪花全局 ID、分库分表、订单路由表
- **社区能力**：帖子/点赞/关注、Feed 推挽结合、ES 全文搜索、时间衰减热榜、DFA 敏感词审核
- **运营能力**：开抢预通知（延迟队列）、到券订阅自动发券、店铺每日 Top 买家

## 文档

| 文档 | 说明 |
|---|---|
| [docs/prd.md](docs/prd.md) | 产品需求文档 |
| [docs/architecture.md](docs/architecture.md) | 架构设计（模块划分/选型/链路图） |
| [docs/database.md](docs/database.md) | 数据库设计（ER 图/12 张表字段说明） |
| [docs/roadmap.md](docs/roadmap.md) | 迭代路线图（勾选跟踪） |
| [docs/middleware-setup.md](docs/middleware-setup.md) | 中间件安装与验证指引 |
| [docs/web-frontend.md](docs/web-frontend.md) | Web 前端设计（localink-web/，M1 后启动） |
| [docs/iterations/](docs/iterations/) | 每个迭代的任务卡（设计取舍/验证记录/学习清单） |
| [AGENTS.md](AGENTS.md) | 项目开发与协作最高规范 |

## 开发状态

**M0~M4 已全部收官（2026-08-18 ~ 2026-09-22）**，进度按技术主题跟踪（一主题=一任务卡+一分支+一 PR，规则见 [AGENTS.md](AGENTS.md)）：

- **M0/M1 工程奠基与基础闭环**：多模块骨架、统一返回与异常、短信验证码、登录鉴权、商户与券 CRUD
- **M2 缓存体系**：Redis 工具框架与 Key 治理 → 缓存三防（穿透/雪崩/击穿，A/B 实测）→ 布隆 + Caffeine 多级读防线（读链路最终形态：本地缓存 → 布隆 → Redis 逻辑过期 → DB 四级纵深）
- **M3 秒杀核心链路（五个主题）**：纯 DB 与超卖实录（基线 QPS 88.8/s）→ 分布式锁与一人一单 → Redis+Lua+Kafka 异步秒杀（QPS 74.9 → 168、峰值吸收 847/s、Avg -81%）→ 一致性闭环（幂等/消费可靠性/对账日志 traceId 三层串联）→ 流量防线（令牌桶/滑动窗口限流、@RateLimit 双维度、前置令牌）
- **M4 数据层扩展**：自研雪花（时钟回拨分档）+ Redis 轮转机器位 → ShardingSphere 5.5.1 双库分片（库 user_id%/表 voucher_id%）+ 订单路由表

当前测试基线 **245/245**（分模块 cache 48 / lock 9 / idempotent 6 / ratelimit 16 / mq 4 / delay 3 / id 9 / server 150）。M5 一致性与运营闭环已收官（3/3 主题）：对账体系、延迟队列与超时关单、通知与运营统计（预通知/订阅自动发券/Top 买家）。M6 社区扩展进行中：M6-A 内容基础（可插拔图片上传+帖子+两级评论）、M6-B 互动关系（点赞事实表+ZSet 点赞榜+关注 Set 共同关注，"DB 事实源+派生视图可重建"第三次落地）已合入。下一步：M6-C Feed 流。

完整进度见 [docs/roadmap.md](docs/roadmap.md)。
