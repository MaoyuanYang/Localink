# 迭代任务卡：M0.4 Maven 多模块骨架

| 字段 | 内容 |
|---|---|
| 迭代编号 | M0.4 |
| 分支 | `feature/m0-4-skeleton` |
| 状态 | 待用户确认 |

---

## 1. 目标

按 `docs/architecture.md` 搭建 12 个 Maven 模块的项目骨架：parent pom 统一版本治理、空框架模块就位、`localink-server` 可启动、引入 Maven Wrapper 免本地安装 Maven。

## 2. 设计取舍

- **为什么导入 BOM（`spring-boot-dependencies` import）而不是继承 `spring-boot-starter-parent`**：Maven 单继承限制——我们自己的 parent pom 要做 12 个模块的统一父级，只能 import BOM；代价是插件版本（如 spring-boot-maven-plugin）需显式声明，已在 server 模块处理
- **为什么模块版本直接写 `0.0.1-SNAPSHOT` 而非 `${revision}`**：单模块项目用 revision+flatten 才有收益，本项目模块不会独立发布，少一个插件少一层复杂度（hmdp-plus 用了 flatten，我们刻意简化并能在面试时说清权衡）
- **为什么提交 Maven Wrapper**：保证任何机器 `./mvnw.cmd` 即可构建，版本锁定 3.9.9，不受本地环境影响
- **为什么 server 现在就依赖 web 并留 PingController**：骨架的验收标准是"可启动、可探活"，`/ping` 是最小冒烟探针，后续迭代保留

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `pom.xml` | parent：12 模块聚合 + 版本治理（Boot 3.5.4/MP 3.5.7/Redisson 3.52/ShardingSphere 5.3.2 等） |
| `localink-*/pom.xml` ×12 | 模块骨架（依赖关系按架构图：starter→common，idempotent→lock） |
| `localink-server/src/.../LocalinkApplication.java` | 启动类 |
| `localink-server/src/.../controller/PingController.java` | `GET /ping` 冒烟探针 |
| `localink-server/src/main/resources/application.yml` | 端口 8086 |
| `mvnw / mvnw.cmd / .mvn/` | Maven Wrapper 3.9.9 |
| `.gitignore` | target/IDE/日志等 |

## 4. 验证记录（2026-07-24 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 全量构建 | `.\mvnw.cmd clean package "-DskipTests"` | BUILD SUCCESS，12 模块全部编译通过 |
| 可执行 jar | server target 产物 | `localink-server-0.0.1-SNAPSHOT.jar` 生成 |
| 启动冒烟 | `java -jar ...` 后请求 `/ping` | 返回 `pong`（8086 端口） |

**排障记录**（本次迭代真实踩坑，已修复）：
1. 4 个模块 pom 的 `xmlns:xsi` 漏写 `=` → Maven 报 Non-parseable POM，修复
2. PowerShell 5.1 执行 `mvn -Dmaven=3.9.9` 时 `-D` 被按 `=` 拆解 → 报 `Unknown lifecycle phase ".9.9"`；**规范：PowerShell 中所有 `-D` 参数必须加引号**（已写入 AGENTS.md 第 10 节）
3. 用 PowerShell `Set-Content` 改中文 XML 导致编码损坏 → 教训：编辑项目文件一律用编辑器/专用工具，不用 shell 重定向

## 5. 学习清单

**核心知识点**
1. Maven 继承 vs 导入：parent 继承（单继承）与 BOM import（scope=import）的区别与使用场景
2. 多模块版本治理：`<dependencyManagement>` 只声明版本不引入依赖，子模块引用免版本号
3. Maven Wrapper 原理：`maven-wrapper.properties` 锁定 Maven 发行版，`mvnw` 脚本自动下载
4. SpringBoot 可执行 jar：`spring-boot-maven-plugin` 的 `repackage` 目标把依赖打进 fat jar（lib/ 目录 + 启动器）

**面试必问题**
1. "dependencyManagement 和 dependencies 的区别？"——声明版本 vs 真实引入
2. "你们项目的模块怎么划分的？依赖规则是什么？"——12 模块图 + 单向依赖（starter→common，业务不依赖业务）
3. "SpringBoot jar 为什么能直接 java -jar 运行？"——repackage + JarLauncher + MANIFEST 的 Main-Class/Start-Class

## 6. 下一步

M0.5 common 模块：统一返回 Result + 错误码枚举 + 异常体系 + 全局异常处理器（第一个真实代码模块）。
