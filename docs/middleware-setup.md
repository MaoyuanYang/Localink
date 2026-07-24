# 中间件环境安装与验证指引（Windows）

> 方式：Docker Compose 一键编排（项目根目录 `docker-compose.yml`）。只读本文即可完成环境搭建。

## 1. 前提

| 软件 | 要求 | 检查命令 |
|---|---|---|
| Docker Desktop | 已安装且守护进程运行中 | `docker info` |
| 端口空闲 | 3306 / 6379 / 9092（后续 9200） | `Test-NetConnection localhost -Port 3306` |

国内拉取镜像慢/失败时：Docker Desktop → Settings → Docker Engine，在 JSON 中添加 `registry-mirrors`（如 `https://docker.m.daocloud.io`），Apply & Restart。

## 2. 服务清单（docker-compose.yml）

| 服务 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| mysql | mysql:8.0 | 3306 | root / localink123（仅本地开发），utf8mb4，数据持久化到命名卷 |
| redis | redis:7 | 6379 | 无密码（仅本地），AOF 持久化开启 |
| kafka | apache/kafka:3.9.1 | 9092 | KRaft 单节点（免 ZooKeeper），自动建 topic |
| kafka-ui | provectuslabs/kafka-ui | 8090 | 可选，profile=`tools`，消息可视化排查利器 |
| elasticsearch | elasticsearch:8.15.5 | 9200 | M6 才启用，profile=`es` |

## 3. 常用命令

```powershell
# 启动核心三件套（日常开发只需要这条）
docker compose up -d mysql redis kafka

# 查看状态与健康检查
docker compose ps

# 启动 Kafka UI（可选）
docker compose --profile tools up -d kafka-ui

# M6 时启动 ES
docker compose --profile es up -d elasticsearch

# 停止 / 彻底重置（删数据卷，慎用）
docker compose down
docker compose down -v
```

## 4. 连通性验证（启动后逐条执行）

```powershell
# MySQL：返回 mysqld is alive
docker exec localink-mysql mysqladmin ping -h localhost -plocalink123

# Redis：返回 PONG
docker exec localink-redis redis-cli ping

# Kafka：能列出版本信息即正常
docker exec localink-kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092

# Kafka 收发冒烟测试（Ctrl+C 退出控制台消费者/生产者）
docker exec localink-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic lk-smoke-test --partitions 1 --replication-factor 1
docker exec localink-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic lk-smoke-test
docker exec localink-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic lk-smoke-test --from-beginning
```

## 5. 故障排查

| 症状 | 处理 |
|---|---|
| 拉镜像超时 | 配置 registry-mirrors 后重试 |
| `docker compose ps` 显示 unhealthy | `docker logs localink-<服务>` 看日志；Kafka 首次格式化存储较慢，等 30s |
| 端口被占用 | `netstat -ano \| findstr :3306` 找到占用进程 |
| Kafka 起不来且日志报 CLUSTER_ID 冲突 | `docker compose down -v` 清卷重来 |

## 6. 与本项目配置的对应关系

`localink-server` 的 `application.yml` 将使用：`localhost:3306`（M1 单库，库名 `localink`）、`localhost:6379`、`localhost:9092`、M6 起加 `localhost:9200`。密码/地址变更时同步改 yml，不要提交到仓库的密钥一律放 `application-local.yml`。
