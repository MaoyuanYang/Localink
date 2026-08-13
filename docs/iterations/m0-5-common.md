# 迭代任务卡：M0.5 common 模块

| 字段 | 内容 |
|---|---|
| 迭代编号 | M0.5 |
| 分支 | `feature/m0-5-common` |
| 状态 | 待用户确认 |

---

## 1. 目标

落地 `localink-common` 第一个真实代码模块：统一返回体 `Result` + 错误码枚举 `BaseCode` + 业务异常 `LocalinkException` + 全局异常处理器，为后续所有迭代的接口建立统一的响应与异常契约（AGENTS.md 第 6 节）。

涉及表：无；涉及接口：无新增业务接口（异常处理为横切能力）；涉及 Redis Key：无。

## 2. 设计取舍

- **全局异常处理器放 common 还是 server**：放 common。理由：异常契约与 Result 是同一套规范，应单点维护；starter 与 server 都能复用。代价：common 需要依赖 spring-webmvc（`BindException` 在 spring-context、`NoResourceFoundException` 在 spring-webmvc，spring-web 均不传递，首次构建踩坑后修正）——声明为 `<optional>true</optional>`，starter 引入 common 时不会传递 web 依赖，server 本身有 web starter 故运行时可用。生效机制：server 启动类在 `com.localink` 根包，组件扫描自动发现 common jar 中的 `@RestControllerAdvice`，零配置
- **HTTP 状态码策略**：业务异常（`LocalinkException`）与参数校验错误返回 HTTP 200 + `Result{code,message}`——业务错误是"预期内的响应"而非传输层错误，前端/Apifox 统一按 body 中 code 断言；资源不存在返回 404，未知系统异常返回 500。备选：全部异常映射 4xx/5xx——被否，业务码与 HTTP 码双轨会让调用方处理两套语义
- **错误码分段**：按 architecture.md 第 5 节约定（0 成功；1xxxx 秒杀券；2xxxx 用户；3xxxx 社区；4xxxx 框架）。M0.5 只落通用/框架段 40000~40004（系统错误/参数错误/未登录/无权限/资源不存在），领域码随各自迭代按需追加，避免预造用不上的枚举
- **BaseCode 用单一枚举而非接口+多枚举**：AGENTS.md 规定错误码统一走枚举 `BaseCode`；单一枚举即全局唯一事实源，IDE 可直接枚举全部错误码。规模膨胀风险在 45 迭代内可控，届时如需拆分再抽 `ResultCode` 接口兼容迁移
- **为什么现在不做 jakarta.validation 异常分支的完整测试**：server 尚未引入 validation starter（M1 接入 DTO 校验时引入），处理器已预留 `BindException` 分支（覆盖 `MethodArgumentNotValidException`，前者是后者的父类），M1 用真实接口验证

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `pom.xml`（parent） | maven-compiler-plugin 增加 `<proc>full</proc>`，显式启用注解处理（Lombok） |
| `localink-common/pom.xml` | 追加 spring-webmvc（optional）、jakarta.servlet-api（provided）、jackson-annotations、slf4j-api、spring-boot-starter-test（test） |
| `com.localink.common.code.BaseCode` | 错误码枚举：SUCCESS(0) + 40000~40004 |
| `com.localink.common.result.Result` | 统一返回体：code/message/data + ok()/fail() 静态工厂 + isSuccess() |
| `com.localink.common.exception.LocalinkException` | 业务异常：携带 code，支持 BaseCode 或自定义 message |
| `com.localink.common.handler.GlobalExceptionHandler` | `@RestControllerAdvice`：业务异常/参数校验/404/请求体不可读/系统异常兜底 |
| `localink-server` 测试 `GlobalExceptionHandlerTest` | MockMvc standalone + 临时 controller 验证四类异常的响应契约 |

## 4. 验证记录（2026-08-13 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 全量构建（含测试） | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块通过 |
| 异常处理器单测 | `GlobalExceptionHandlerTest`（MockMvc standalone） | 3/3 通过：业务异常→200+业务码；NoResourceFound→404+40004；未知异常→500+40000 且不泄漏堆栈 |
| 启动冒烟回归 | `java -jar ...` + `curl /ping` | 返回 `pong`（8086） |
| 404 契约实测 | `curl http://localhost:8086/nope` | HTTP 404 + `{"code":40004,"message":"资源不存在","data":null}`，证明 common jar 中的 @RestControllerAdvice 被 server 组件扫描自动装配 |

**排障记录**（本次迭代真实踩坑，均已修复）：
1. 初版依赖 spring-web → `BindException`（在 spring-context）、`NoResourceFoundException`（在 spring-webmvc）编译失败，spring-web 两者均不传递 → 改依赖 spring-webmvc（optional）
2. `NoResourceFoundException` 继承 `jakarta.servlet.ServletException`，而 spring-webmvc 对 servlet-api 是 provided 不传递 → common 显式声明 jakarta.servlet-api（provided）
3. Lombok 生成代码全部失效（getCode/log/setter 均缺失）→ JDK 21 对 classpath 隐式注解处理只发警告、后续版本将默认禁用；parent pom 的 maven-compiler-plugin 显式配置 `<proc>full</proc>` 后恢复
4. `Result.isSuccess()` 被 Jackson 按 bean 规则序列化为响应中的 `success` 字段，破坏 code/message/data 三字段契约 → `@JsonIgnore` 排除（common 引入 jackson-annotations）

## 5. 学习清单

**核心知识点**
1. `@RestControllerAdvice` 原理：基于 AOP 拦截 controller 抛出的异常，`@ExceptionHandler` 按异常类型就近匹配（含子类）
2. 统一返回体设计：业务码与 HTTP 码的分工，为什么国内主流选择 HTTP 200 + body code
3. Maven `<optional>` 依赖：编译期可用、不传递给下游——框架模块控制依赖面污染的手段
4. 组件扫描边界：`@SpringBootApplication` 默认扫描启动类所在包及子包，跨 jar 的 Bean 只要在扫描路径内即可被发现

**面试必问题**
1. "全局异常处理器是怎么生效的？"——DispatcherServlet 捕获异常 → HandlerExceptionResolver → @ExceptionHandler 匹配
2. "你们项目的错误码是怎么设计的？"——分段枚举、单点维护、按迭代增量扩展
3. "业务异常和系统异常怎么区分处理？"——业务异常 warn 级日志 + 200 返回；系统异常 error 级日志 + 500，避免向前端泄漏堆栈

## 6. 下一步

M0.6 Git 规范落地：.gitignore 检查、分支策略与 Conventional Commits 演示 PR（M0.4 已实际执行过一轮，M0.6 做规范确认与收尾）。
