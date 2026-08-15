# 迭代任务卡：M1.2 单库建表 SQL + HikariCP + MyBatis-Plus 接入

| 字段 | 内容 |
|---|---|
| 迭代编号 | M1.2 |
| 分支 | `feature/m1-2-datasource` |
| 状态 | 已完成（2026-08-15 用户学习确认，roadmap 已勾选） |

---

## 1. 目标

把 M1.1 的 12 张表设计落成可运行的数据层：`sql/localink.sql`（DDL + 种子数据）+ localink-server 接入 HikariCP 数据源与 MyBatis-Plus，并验证"连接池 → MySQL → 真实表"全链路。

涉及表：12 张全部建齐；涉及接口：无新增；涉及 Redis Key：无。

## 2. 设计取舍

- **种子数据用虚构商户，不搬参考项目数据**：铁律 1 禁止复制 hmdp-plus 源码，数据同理（其种子是真实商户信息）；虚构 10 类型 + 10 商户（含杭州区域经纬度，供 M6.18 GEO 演示）。只种商户域：券数据留给 M1.7/M1.8 走接口创建，用户数据留给 M1.4 注册流程产生——数据跟着业务走，不预造
- **建表一次建齐 12 张，而非按迭代分批建**：结构在 M1.1 已定稿，分批建表意味着后续迭代要穿插 DDL 变更（改表风险 > 建空表成本）；空表零代价，业务迭代只写 DML
- **配置分层：application.yml 放开箱默认值，机器差异走 application-local.yml**：默认值与 docker-compose 完全一致（root/localink123/3306），clone 下来 `docker compose up -d mysql` 即可跑，求职作品"一键可运行"优先；某人要改密码/端口时建 `application-local.yml`（M0.6 已布防 gitignore）覆盖，默认配置永不被个性化污染
- **Hikari 参数取保守值**：maximum-pool-size 10 / minimum-idle 2（单机演示无高并发，M7 压测时再调）；connection-timeout 3s（快速失败优于长时间挂起）；idle-timeout 10min / max-lifetime 30min（低于 MySQL wait_timeout 默认 8h，防止池内持有被服务端关闭的死连接）
- **JDBC URL 四个参数各解决一个问题**：`useSSL=false`（本地无证书，跳过 TLS 握手告警）、`serverTimezone=Asia/Shanghai`（驱动时区显式化，与容器 TZ 一致）、`allowPublicKeyRetrieval=true`（MySQL 8 caching_sha2_password 首次连接需要）、`characterEncoding=utf8`（连接层编码）
- **全局 id-type=assign_id**：MP 内置雪花，与 database.md"主键雪花 BIGINT"约定对齐；M4 切自研 id-starter 时只需替换 ID 生成器 Bean，实体注解不动
- **分页拦截器现在就配**：PaginationInnerInterceptor 是 MP 唯一必须手动注册的内置拦截器，M1.6 商户分页必用；集成时一次配好比到时回头改配置类干净。代价：一个暂时无用的 Bean
- **无实体阶段的集成验证用 JdbcTemplate 探针**：实体类随业务迭代按需建（用户确认的决策），M1.2 用 `@SpringBootTest` + JdbcTemplate 直查验证"上下文装配 + 连接池 + 真实表 + 种子数据"四件事；**MP 的 mapper 映射/CRUD 能力验证显式顺延到 M1.4/M1.6 首个实体落地时**，不留暗账
- **集成测试依赖真实 MySQL，不用 H2**：H2 的方言差异（utf8mb4_0900_ai_ci、`bigint unsigned`、information_schema 行为）会让测试给出假信心；本项目开发环境文档化要求 docker 中间件（README 快速开始第 1 步），测试与运行环境一致是刻意选择。代价：无 MySQL 环境 `mvnw test` 会失败——已在 README 快速开始中隐含（先起 mysql）

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `sql/localink.sql` | CREATE DATABASE + 12 表 DDL（严格按 database.md）+ 种子数据（10 类型 + 10 虚构商户） |
| `localink-server/pom.xml` | + mybatis-plus-spring-boot3-starter（3.5.7 走父 POM）、mysql-connector-j（runtime，走 BOM）；HikariCP 随 jdbc starter 传递 |
| `application.yml` | datasource（Hikari 五参数）+ mybatis-plus 全局配置（banner 关、id-type assign_id、驼峰映射） |
| `com.localink.config.MybatisPlusConfig` | @MapperScan("com.localink.**.mapper") + 分页拦截器 |
| `DatasourceSmokeTest` | @SpringBootTest 集成测试 ×3：类型种子=10、商户种子≥10、lk_ 表数=12 |
| `docs/roadmap.md` | 勾选 M1.1（2026-08-15 用户确认） |

## 4. 验证记录（2026-08-15 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| MySQL 就绪 | `docker compose up -d mysql` + healthcheck | healthy |
| 建库建表 | `docker compose exec -T mysql mysql -uroot -p*** < sql/localink.sql` | exit=0 |
| 表数量 | `SHOW TABLES` | 12 张 lk_ 表齐全 |
| 种子数据 | `SELECT COUNT(*) FROM lk_shop_type / lk_shop` | 10 / 10，中文与经纬度存储正常 |
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 测试 | 同上 | 6/6 通过（3 异常处理器 + 3 数据源冒烟） |
| 启动冒烟 | `java -jar localink-server-...jar` + `curl /ping` | pong（8086），带数据源上下文启动无异常 |

**排障记录**：
1. Docker Desktop 未运行导致 `docker compose up` 报 npipe 连接失败 → 启动 Docker Desktop 并轮询 `docker info` 至守护进程就绪（本机环境步骤，非代码问题）
2. PowerShell 无 `<` 重定向且管道传中文有编码风险 → 建库脚本经 `cmd /c` 重定向执行，字节流不被重编码

## 5. 学习清单

**核心知识点**
1. HikariCP 池参数语义：maximum-pool-size/minimum-idle/connection-timeout/idle-timeout/max-lifetime 各自防什么问题；max-lifetime 为何必须低于服务端 wait_timeout
2. 连接池懒初始化：Hikari 在首次 getConnection 才建池，所以"应用启动成功"不等于"数据库可连"——集成测试必须发真实查询
3. MySQL 8 连接参数：caching_sha2_password 与 allowPublicKeyRetrieval 的关系、serverTimezone 与容器 TZ 的一致性要求
4. MyBatis-Plus 装配链：starter 自动配置 SqlSessionFactory + MapperScannerRegistrar；@MapperScan 的包匹配规则；插件走 MybatisPlusInterceptor 责任链（分页是 InnerInterceptor）
5. 雪花 ID 在 MP 中的落点：IdType.ASSIGN_ID 全局配置 vs @TableId 注解优先级；long 型 ID 返前端需转 String 防 JS 精度丢失（M1.4 接口落地时处理）

**面试必问题**
1. "连接池为什么能提升性能？"——连接创建（TCP 三次握手 + 认证 + 会话初始化）是昂贵操作，池化复用 + 预热（minimum-idle）把均摊成本降到借还级别
2. "你的分页是怎么实现的？"——MP 分页拦截器在 SQL 执行前改写语句（count + limit），方言适配 DbType；相比手写 limit 参数，拦截器保证 count 与数据查询条件一致
3. "为什么不用 H2 做测试？"——方言差异导致假信心；测试环境与运行环境一致优先于测试便利性，中间件 docker 化后"真实依赖"的成本已经很低
4. "数据库密码直接放 application.yml 安全吗？"——演示项目 + 本地 docker 默认密码，可接受；生产做法是环境变量/配置中心注入，本项目用 application-local.yml（gitignore 拦截）承载个性化覆盖，防线在 M0.6 已建

## 6. 下一步

M1.3 发送短信验证码：Redis 首次登场（spring-boot-starter-data-redis + RedisCache 最简封装）、验证码生成/存储（String + TTL）/发送模拟（日志），接口 `POST /api/sms/code`。
