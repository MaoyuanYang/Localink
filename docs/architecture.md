# Localink 架构设计文档

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-07-24 | 初版（M0.2 产出） |

---

## 1. 架构总览

Localink 采用**单体应用 + 框架组件化**架构：一个可启动的业务应用（`localink-server`）+ 一组自研 starter 框架模块。不做微服务拆分——业务规模不需要，且面试考察的是组件深度而非服务数量。

```
┌────────────────────────────────────────────────────────┐
│                    localink-server                     │
│  controller → service → mapper │ kafka │ delay │ init  │
├────────────────────────────────────────────────────────┤
│  cache-starter │ lock-starter │ idempotent-starter     │
│  ratelimit-starter │ mq-starter │ delay-starter        │
│  id-starter │ bloom(并入cache) │ search-starter(M6)    │
├────────────────────────────────────────────────────────┤
│  localink-api-model │ localink-common │ localink-sharding
├────────────────────────────────────────────────────────┤
│  MySQL(→ShardingSphere) │ Redis │ Kafka │ ES(M6)       │
└────────────────────────────────────────────────────────┘
```

## 2. Maven 模块划分

| 模块 | 职责 | 依赖方 |
|---|---|---|
| `localink-common` | Result、错误码枚举、异常体系、业务枚举、常量、Spring 上下文工具 | 所有模块 |
| `localink-api-model` | DTO/VO 载体 + jakarta.validation 校验注解 | server |
| `localink-cache-starter` | RedisTemplate 配置、RedisCache 封装、Key 治理、Caffeine 本地缓存、布隆过滤器（Redisson） | server |
| `localink-lock-starter` | RedissonClient 封装、四种锁、@ServiceLock 注解 AOP、命令式工具 | server、idempotent |
| `localink-idempotent-starter` | @RepeatExecuteLimit 幂等注解（结果标记+本地锁+分布式公平锁） | server |
| `localink-ratelimit-starter` | 令牌桶/滑动窗口 Lua 限流、场景化配置、秒杀访问令牌 | server |
| `localink-mq-starter` | Kafka 消息模型、生产者/消费者模板基类、发送/消费钩子 | server |
| `localink-delay-starter` | Redisson 延迟队列分片 + 线程池消费框架 | server |
| `localink-id-starter` | 雪花算法 + Redis Lua 分配 workId | server |
| `localink-sharding` | ShardingSphere 集成与 snakeyaml 兼容处理 | server |
| `localink-search-starter` | ES 客户端封装（M6 引入） | server |
| `localink-server` | 唯一可启动业务应用（端口 **8086**），聚合全部 starter | — |

**依赖规则**（单向、禁止循环）：
1. starter 只允许依赖 `localink-common`，不允许依赖 `localink-server` 和其他业务 starter（idempotent 例外，可依赖 lock）
2. `localink-server` 依赖全部 starter 与 `localink-api-model`
3. 各 starter 通过 `META-INF/spring/...AutoConfiguration.imports` 自动装配，server 零配置引入

## 3. 技术选型表

| 能力 | 选型 | 理由 | 备选（为什么不用） |
|---|---|---|---|
| 缓存 | Redis 7 + Redisson | 生态成熟；Redisson 提供锁/布隆/延迟队列全家桶 | 自研 RedisTemplate 锁（语义不全） |
| 本地缓存 | Caffeine | 高性能、支持自定义过期策略 | Guava Cache（停止演进） |
| 消息队列 | Kafka 3.x | 高吞吐、acks=all+幂等生产者满足可靠性；大厂主流 | RabbitMQ（吞吐低）、Redis Stream（无持久化保障） |
| ORM | MyBatis-Plus 3.5.7 | 单表 CRUD 零 XML，复杂 SQL 可回退 XML | JPA（复杂场景控制力弱） |
| 分库分表 | ShardingSphere-JDBC 5.3.2 | 应用内集成无需运维中间件 | MyCat（需独立部署） |
| 搜索 | Elasticsearch 8.x（M6） | 全文检索事实标准 | MySQL LIKE（不可用级性能） |
| 本地锁 | Caffeine 承载 ReentrantLock | 与本地缓存同组件，减少依赖 | ConcurrentHashMap 裸存（无过期清理） |
| JSON | fastjson2 | 性能与泛型支持 | Jackson（Spring 默认，Web 层保留） |
| API 文档 | Knife4j 4.x | 国产、UI 友好（按需引入） | springdoc 原生 |

## 4. 核心链路设计（秒杀下单目标态）

```
申请令牌          下单                                    异步建单
  │  ①限流(ISSUE_TOKEN)                                    │
  ├─►②发一次性令牌(Redis, TTL 30s)                          │
  │                │  ③限流(SECKILL_ORDER)                  │
  │                ├─►④令牌消费(Lua 原子 GET+DEL)           │
  │                ├─►⑤券详情多级缓存查询 + 人群校验         │
  │                ├─►⑥Lua 原子扣减(库存/一人一单/流水)      │
  │                └─►⑦发 Kafka ──► ⑧消费:幂等→DB扣减兜底    │
  │                                    →写订单+路由表+对账日志│
  └── 失败任一环节 ─► ⑨回滚:DEL库存key回源DB + 对账日志 + 退避重试
```

**演进路径**（面试叙事线）：M3.1 纯 DB（压测超卖）→ M3.5 乐观锁+分布式锁 → M3.6 Redis+Lua → M3.8 Kafka 异步 → M3.10~12 幂等/回滚/对账补齐闭环 → M3.13~15 限流与令牌前置。每一代解决上一代的具体问题，最终形态即上图。

## 5. 关键横切设计约定

| 约定 | 规范 |
|---|---|
| Redis Key | 统一前缀 `lk:`（环境可配），经 KeyManage 枚举 + KeyBuild 生成；集群场景用 `{voucherId}` hash tag 保证同槽位 |
| Kafka Topic | 统一前缀 `lk-`（配置项 `localink.topic.prefix`），如 `lk-seckill-order`；DLQ 为 `{topic}.DLQ` |
| 锁命名 | `lk-lock:{类型前缀}:{业务名}:{SpEL解析key}` |
| 错误码 | 分段：0 成功；1xxxx 秒杀券；2xxxx 用户；3xxxx 社区；4xxxx 框架；5xxxx 商户 |
| 幂等键 | MQ 消息 UUID + 业务唯一键（userId+voucherId）双重保障 |
| 时间 | 统一 `LocalDateTime`，Jackson 格式化 `yyyy-MM-dd HH:mm:ss` |
| 序列化 | Redis 全部 String 序列化，对象值用 fastjson2 转 JSON |

## 6. 数据架构演进

| 阶段 | 形态 | 说明 |
|---|---|---|
| M1~M3 | 单库 `localink` | 快速跑通业务与中间件链路 |
| M4 | 双库 `localink_0/1` × 2 表 | ShardingSphere-JDBC；订单库按 user_id、表按 voucher_id 混合分片；订单路由表解决按 orderId 查询 |
| M6 | + ES 索引 | 帖子/商户经 Kafka 同步到 ES，DB 为唯一事实源 |

## 7. 部署形态

本机开发：单 JVM（localink-server:8086）+ MySQL 8 + Redis 7 + Kafka 3.x（KRaft 模式，免 ZK）+ ES 8.x（M6）。文档 `docs/middleware-setup.md`（M0.3 产出）。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| ShardingSphere 5.3.2 与 SpringBoot3/snakeyaml 兼容问题 | `localink-sharding` 模块内做兼容处理（M4.4 验证） |
| Kafka 本地资源占用高 | KRaft 单节点 + 关闭非必要 topic 自动创建 |
| 演进式开发导致早期代码被推翻 | 每代实现保留在独立方法/类中（如 `seckillV1/V2`），作为压测对比素材而非删除 |
