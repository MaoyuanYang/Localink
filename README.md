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

M0 工程奠基与 M1 基础业务闭环（单库）已全部完成（2026-08-18），下一步进入 M2 缓存体系或 W0 前端骨架。完整进度见 [docs/roadmap.md](docs/roadmap.md)。
