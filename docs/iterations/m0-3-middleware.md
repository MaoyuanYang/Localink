# 迭代任务卡：M0.3 中间件环境安装

| 字段 | 内容 |
|---|---|
| 迭代编号 | M0.3 |
| 分支 | `feature/m0-3-middleware` |
| 状态 | 待用户确认 |

---

## 1. 目标

用 Docker Compose 一键编排本地中间件（MySQL 8 / Redis 7 / Kafka 3.9 KRaft），产出安装验证指引文档并完成连通性验证，为 M1 开发备好环境。

## 2. 设计取舍

- **为什么 Docker Compose 而非 Windows 原生安装**：Redis 无官方 Windows 版、Kafka 在 Windows 原生运行坑多；Compose 文件入库后环境可一键复现，本身就是项目工程化能力的一部分（简历/部署文档素材）
- **为什么 Kafka 用 KRaft 模式**：Kafka 3.x 官方推荐，免 ZooKeeper，本地少一个进程，且这是面试加分点（"ZK 移除"是 Kafka 架构演进热点题）
- **为什么 ES/kafka-ui 用 Compose profiles 隔离**：日常开发不启动 ES（占内存），M6 时 `--profile es` 按需拉起；kafka-ui 作为可选排障工具
- **为什么 MySQL 不开 binlog 等额外配置**：M1 单库阶段用不到；M4 分片时另起 `localink_0/1` 两库，M6 若选 Canal 方案再开（届时在任务卡中说明）

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `docker-compose.yml` | 5 服务编排：mysql/redis/kafka + tools(es) 两个 profile |
| `docs/middleware-setup.md` | 安装、启停、验证、排查完整指引 |

## 4. 验证记录（2026-07-24 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 容器健康 | `docker compose ps` | mysql/redis/kafka 均 `Up (healthy)` |
| MySQL | `mysqladmin ping` | `mysqld is alive` |
| 建库 | `CREATE DATABASE localink` | 成功，`SHOW DATABASES` 可见 |
| Redis | `redis-cli ping` | `PONG` |
| Kafka 连通 | `kafka-broker-api-versions.sh` | 返回 broker 信息（id: 1） |
| Kafka 收发 | 创建 `lk-smoke-test` topic | 创建成功并可列出 |

## 5. 学习清单

**核心知识点**
1. Docker Compose 核心概念：service / volume（命名卷持久化）/ healthcheck / profiles（按需启停）
2. Kafka KRaft 架构：broker+controller 合一、`CLUSTER_ID`、为何移除 ZooKeeper（元数据自管理，减少运维与脑裂风险）
3. MySQL 容器化要点：字符集 utf8mb4、时区、数据卷持久化（容器删了数据还在）
4. Redis AOF：appendonly yes 的意义（与 RDB 的区别——这是 M3/M5 故障题的铺垫）

**面试必问题**
1. "Kafka 为什么去掉 ZooKeeper？"——KRaft 元数据自管理、架构简化、 Controller Quorum
2. "Redis 持久化方式有哪些？区别？"——RDB 快照 vs AOF 日志，本项目开 AOF
3. "本地开发环境怎么保证可复现？"——Compose 文件入库 + profiles 按需启停

## 6. 下一步

M0.4 Maven 多模块骨架：parent pom + 12 个空子模块 + 可启动的 localink-server + Maven Wrapper。
