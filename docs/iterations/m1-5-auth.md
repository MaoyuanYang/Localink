# 迭代任务卡：M1.5 双拦截器鉴权 + UserHolder

| 字段 | 内容 |
|---|---|
| 迭代编号 | M1.5 |
| 分支 | `feature/m1-5-auth-interceptors` |
| 状态 | 待用户确认 |

---

## 1. 目标

让 M1.4 发出的 token 真正生效：双拦截器（token 刷新 + 登录校验）+ UserHolder（ThreadLocal）+ 首个登录态接口 `GET /api/user/me`，并定稿前后端认证契约（Authorization 头 + 40002 会话失效码）。PRD F-ACC-02 落地。

涉及表：无新增；涉及接口：`GET /api/user/me`；涉及 Redis Key：`lk:user:token:{token}`（读 + EXPIRE 刷新）。

## 2. 设计取舍

- **双拦截器职责分离，顺序即语义**：TokenRefreshInterceptor（order 0，/api/**，只认 token 不拦截——有会话则写 UserHolder + 刷新 TTL，无则放行）→ LoginInterceptor（order 1，/api/user/** 排除 login，只看 UserHolder 是否为空）。拆两层而非合一：公开接口（登录/发码/后续的商户浏览）需要匿名通过刷新层，受保护接口才走校验层——合并成一个拦截器就要在内部维护路径白名单，职责混乱
- **头字段 `Authorization`，值=裸 token（无 Bearer 前缀）**：web-frontend.md 把命名权交给 M1.5，本迭代定稿并回写文档；不透明会话 ID 不是 JWT，Bearer scheme 只是仪式。前端 axios 拦截器照此注入
- **未登录 = HTTP 200 + code 40002**：沿用 M0.5 约定（业务错误走 body code，仅 404/500 用 HTTP 状态）；拦截器抛 `LocalinkException(UNAUTHORIZED)`，经异常处理器转换。**同时修正 web-frontend.md 的契约笔误**（原文写"捕获 2xxxx 未登录码"，与 M0.5 错误码分段冲突，改为 40002）——跨文档矛盾在发现处解决，不留给前端联调
- **UserDTO.id 用 `@JsonSerialize(ToStringSerializer)`**：/api/user/me 是首个返回雪花 ID 的接口，JS Number 53 位精度装不下 19 位雪花 ID；注解方式保留 Java 侧 Long 类型，仅 JSON 出口转字符串。偿还 M1.4 登记的精度债
- **afterCompletion 清理 ThreadLocal**：Tomcat 线程池复用线程，不清理会导致下个请求读到上个用户——写在刷新拦截器的 afterCompletion（无论哪条路径都会执行）
- **TTL 刷新=每次认证请求 EXPIRE 1800**：滑动过期语义"活跃即续命"，不活跃 30 分钟自然登出；演示规模下每请求一次写命令可接受
- **路径策略最小闭环**：校验只盖 `/api/user/**` 排除 `/api/user/login`；M1.6 商户浏览是公开接口天然不受影响。随迭代扩展 include/exclude，不预造大全
- **会话 Hash→UserDTO 映射暂为拦截器私有方法**：当前唯一消费方；出现第二个（如资料修改同步会话）再抽 SessionService

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `com.localink.api.dto.UserDTO` | id（ToStringSerializer）/phone/nickName/icon/level；api-model + jackson-databind 依赖 |
| `com.localink.framework.holder.UserHolder` | ThreadLocal\<UserDTO\>：set/get/clear |
| `com.localink.framework.auth.TokenRefreshInterceptor` | 读 Authorization → HGETALL → UserHolder + EXPIRE 刷新；afterCompletion 清理 |
| `com.localink.framework.auth.LoginInterceptor` | UserHolder 空 → LocalinkException(UNAUTHORIZED) |
| `com.localink.config.AuthWebConfig` | WebMvcConfigurer：注册顺序 + 路径模式 |
| `UserController` | + GET /api/user/me（从 UserHolder 取） |
| `docs/web-frontend.md` | 认证头定稿（Authorization 裸 token）+ 会话失效码修正（2xxxx→40002） |
| `AuthInterceptorIntegrationTest` | @SpringBootTest+@AutoConfigureMockMvc ×5：带 token 返回用户（id 为字符串）、无 token 40002、伪造 token 40002、TTL 100→刷新回 1800、login 不被误拦 |

## 4. 验证记录（2026-08-17 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 测试 | 同上 | **21/21 通过**（+5 鉴权集成） |
| 冒烟（脚本化） | `powershell -File smoke-m15.ps1` | 登录得 32 位 token → me 带 token：`{"id":"2089263735544778753",...}`（**id 为字符串**）→ me 无 token：40002 → TTL 100→1800 刷新生效 → CLEANUP-OK 无残留 |

**排障记录**：
1. 首次构建 21 个集成测试全灭（Redis/MySQL 双连不上）——机器重启后 Docker Desktop 未自启，与代码无关；启动 Docker Desktop → 容器随 `restart: unless-stopped` 自动恢复双 healthy → 重跑全绿。启示：**集成测试全灭先看中间件，再看代码**

## 5. 学习清单

**核心知识点**
1. HandlerInterceptor 执行模型：preHandle 抛异常同样走 HandlerExceptionResolver（@RestControllerAdvice 生效）；afterCompletion 无论异常与否都执行——清理资源的正确位置
2. ThreadLocal 与线程池：请求线程是复用的，set 不 clear = 数据串请求；InheritableThreadLocal 在线程池场景同样失效（M3 异步链路会遇到）
3. 拦截器 vs 过滤器 vs AOP：拦截器在 DispatcherServlet 内、能拿 HandlerMethod、随 MVC 生命周期；本场景选拦截器因为只需要路由级控制
4. 滑动过期会话：每次访问 EXPIRE 续命 vs 固定过期——活跃用户不掉线、僵尸会话自动回收的实现机制
5. Jackson 序列化定制：@JsonSerialize(ToStringSerializer) 字段级出口转换，Long 精度问题的标准解法（对比全局配置 ObjectMapper）

**面试必问题**
1. "双拦截器为什么要分两个？"——刷新层不拦截（公开接口匿名通过），校验层只裁决；合一就要路径白名单，职责耦合。顺序由注册 order 保证
2. "ThreadLocal 会有什么问题？"——线程池复用导致串数据，afterCompletion 必须 clear；追问线程池场景（异步任务传递上下文要 TaskDecorator）
3. "为什么未登录返回 200+40002 而不是 HTTP 401？"——项目契约：业务错误走 body code 单轨，前端拦截器统一按 code 处理；HTTP 状态只表达传输层
4. "前端怎么知道登录态失效？"——响应拦截器捕获 40002 → 清 authStore → 跳登录页；契约写在 web-frontend.md，本迭代定稿

## 6. 下一步

M1.6 商户类型 + 商户 CRUD：分页插件首次实战（Page\<Shop\>）、公开查询接口（不落入 LoginInterceptor 范围）、DTO/VO 转换，商户域业务正式开始。
