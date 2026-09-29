# Localink 部署手册

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-09-29 | M7-B 产出：后端单机完整部署流程（M0~M7 全量） |

本文覆盖从零把后端跑起来的全流程。中间件的安装与排障不在此重复，见 [middleware-setup.md](middleware-setup.md)。

## 1. 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21（编译目标 17） | `java -version` 确认 |
| Docker Desktop | 任意近期版本 | 承载 MySQL/Redis/Kafka/ES；WSL2 后端 |
| Maven | 使用仓库自带 `mvnw.cmd`，无需全局安装 | PowerShell 中 `-D` 参数必须加引号 |
| 操作系统 | Windows（Git Bash / PowerShell） | 脚本按 Windows 编写 |

## 2. 中间件启动

```powershell
docker compose up -d mysql redis kafka          # 基础三件套
docker compose --profile es up -d elasticsearch # 搜索功能需要（ik 自动条件安装）
docker compose ps                                # 确认全部 healthy
```

连通性验证（mysqladmin ping / redis ping / kafka 收发冒烟 / ES ik 分词验证）见 [middleware-setup.md](middleware-setup.md) §4。

## 3. 数据库初始化（首次部署必须，顺序不可反）

```bash
# ① 主库 localink：建库 + 12 张表（10 非分片业务表 + 订单/对账日志的逻辑表）+ 种子数据
docker exec -i localink-mysql mysql -uroot -plocalink123 < sql/localink.sql

# ② 分片物理表：localink 与 localink_1 两库各建 lk_voucher_order_0/1、lk_voucher_reconcile_log_0/1，
#    并在主库建 lk_order_route 路由表（① 中的逻辑订单表在运行期不被 ShardingSphere 使用，留作 DDL 参照）
docker exec -i localink-mysql mysql -uroot -plocalink123 < sql/sharding.sql
```

验证（两库各应有订单物理表，主库应有种子商户）：

```bash
docker exec localink-mysql mysql -uroot -plocalink123 -N -e "SHOW TABLES FROM localink LIKE 'lk_voucher_order%'"   # 期望 2 行
docker exec localink-mysql mysql -uroot -plocalink123 -N -e "SHOW TABLES FROM localink_1"                            # 期望 4 行
docker exec localink-mysql mysql -uroot -plocalink123 -N -e "SELECT COUNT(*) FROM localink.lk_shop"                 # 期望 10（种子）
```

> 连接信息默认 `root / localink123`，与 `application.yml` 的 `spring.datasource` 和 `localink.sharding.datasources.ds_1` 对应；改密码须三处同步（详见 [middleware-setup.md](middleware-setup.md) §6）。

## 4. 构建与启动

```powershell
.\mvnw.cmd clean package "-DskipTests"           # 全量构建（约 1~2 分钟）
java -Xms512m -Xmx2g -jar localink-server\target\localink-server-0.0.1-SNAPSHOT.jar
```

- 启动约 25~40 秒（含初始化器与 Kafka 消费者组加入）。
- 后台运行可用仓库脚本：`powershell -File scripts\smoke.ps1`（启动 → 30s 内探活 → 自动清理进程树，适合验证性启动）。
- 生产化参数参考（性能档案见 [architecture.md](architecture.md) §8）：`-Xms` 与 `-Xmx` 设一致避免运行期扩容抖动。

## 5. 冒烟验证

```bash
curl http://localhost:8086/ping                  # → pong
curl http://localhost:8086/api/shop/1            # → {"code":0,...,"data":{...}} 种子商户
```

## 6. 启动自检清单（四条日志 + 一条消费组证据）

启动完成后在应用日志中确认（任何一条缺失即初始化不完整）：

| # | 日志关键字 | 含义 |
|---|---|---|
| 1 | `商户布隆过滤器灌入完成, count=N` | 布隆预热（N=当前商户数） |
| 2 | `秒杀库存回灌完成` | 未结束活动的 Redis 库存与主库对齐 |
| 3 | `商户 GEO 灌入完成, total=N, indexed=M` | GEO 坐标索引 |
| 4 | `对账完成: 差异补偿=X笔` | 对账 Job 首轮执行（@EnableScheduling 生效标志） |

Kafka 消费组就绪证据：

```bash
docker exec localink-kafka bash -c "/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list" | grep localink-server
# 期望三组：localink-server-seckill-order / -post-audit / -post-search*
```

## 7. Elasticsearch 索引与全量重建

- 索引在首次写入时自动创建（ensureIndex 等待分片就绪），无需手工建。
- 帖子数据经 Kafka 增量同步（upsert 幂等）；**DB 是唯一事实源**，ES 是可重算派生视图。
- 全量重建：`PostSearchService.rebuildAll()`（删索引 → 全量重灌）。当前无 HTTP 管理端点（避免误触发），经集成测试或开发工具调用；管理端点属后续演进项。

## 8. 压测复现入口

三场景压测资产与复现命令见 [scripts/jmeter/README.md](../scripts/jmeter/README.md)（拒绝 40002/10007、缓存命中、成功路径两步流；造数脚本含绕过发码频控的快速版）。压测是**侵入性操作**：结束必须清扫数据并复查计数（分片环境按物理表名清理），详见任务卡 m7-load-test.md 排障实录 10/11。

## 9. 常见问题

| 现象 | 处置 |
|---|---|
| 启动报 `Too many connections`（MySQL 151） | 多实例/测试并存时连接超限；确认没有并行测试 JVM，重启 MySQL 释放残留 |
| ES 连接失败 / ik 未生效 | 见 [middleware-setup.md](middleware-setup.md) §5 ik 手工补装与 §6 排查表 |
| 管理端点越权（对外部署） | `application.yml` 置 `localink.security.admin-guard.enabled: true` 并配置 `admin-phones` 白名单（M8 起商户/券写端点支持 @AdminOnly 闸门，默认关闭=演示口径） |
| 端口 8086 占用 | `netstat -ano | findstr :8086` 找 PID 清理；smoke.ps1 已处理 javapath shim 子进程坑 |
| 分片查询报错 | 检查 localink_1 是否已初始化（§3 步骤②遗漏是最常见原因） |
| Docker Desktop 自动停止 | 重新 `Start-Process Docker Desktop` 后 `docker compose up -d`（容器随 daemon 自启） |

## 10. 前端部署（待 W4）

Web 前端线（`localink-web/`，React 18 + Vite）尚未启动，nginx 部署与后端反代配置待 W4 主题落地后补充于此节。
