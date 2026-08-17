# 迭代任务卡：M1.4 登录/注册

| 字段 | 内容 |
|---|---|
| 迭代编号 | M1.4 |
| 分支 | `feature/m1-4-user-login` |
| 状态 | 待用户确认 |

---

## 1. 目标

完成登录闭环：`POST /api/user/login`——消费验证码（原子 GETDEL）→ 按手机号查用户 → 不存在即自动注册（lk_user 实体与 Mapper 首次落地）→ UUID token → Redis Hash 会话（TTL 30min）→ 返回 token。

涉及表：`lk_user`；涉及接口：`POST /api/user/login`；涉及 Redis Key：`lk:user:token:{token}`（Hash，TTL 1800s）。

## 2. 设计取舍

- **验证码用 GETDEL 原子消费（`opsForValue().getAndDelete()`）**：呼应 M1.3 登记的 hasKey→set 竞态——单 key 场景 Redis 6.2+ 原生命令即可原子化，取空=过期/未发送。与 M3 的多 key 原子（库存+一人一单+流水，只能 Lua）形成演进对照：能用原生命令的不上脚本
- **错误码拆 20002/20003**：GETDEL 取空（验证码过期/从未发送）与"码不匹配"分开提示，前端可差异化引导重新获取
- **会话字段平铺 Hash（id/phone/nickName/icon/level）而非单字段 JSON**：资料修改（F-ACC-03）可 HSET 单字段同步会话；M1.5 拦截器逐字段读取。代价：字段增减需同步改读写两处
- **UUID token 而非 JWT**：会话状态存 Redis（服务端可控、可踢人、可刷新 TTL），JWT 的无状态恰恰丢掉这些能力；UUID 无泄露信息、生成零成本。M1.5 的 token 刷新拦截器依赖服务端会话才成立
- **自动注册 insert + update 两条 SQL**：nick_name 默认"用户{id}"，而 id 由 MP 在 insert 时生成（ASSIGN_ID 在 SQL 执行前完成赋值），注册前无法预知 id，故 insert 后 updateById 回填。代价：注册一次两条 SQL，仅首次登录发生
- **登录响应只返回 token**：用户信息由 M1.5 之后的 `/api/user/me` 提供，不超前开发
- **会话写入 putAll+expire 两条命令**：Hash 无 HSET+EXPIRE 复合原生命令，存在极小崩溃窗口（会话 key 无 TTL 残留）；SessionCallback(MULTI/EXEC) 或 Lua 可原子化——当前接受，任务卡显式记录，不扩大本迭代范围
- **long ID 精度**：会话 Hash 存字符串天然无精度问题；未来 API 直接返回 id 时再做 long→String（Jackson 全局配置或 DTO String 化），预登记
- **测试数据策略**：集成测试用固定测试手机号 13900139002，@AfterEach 清理 Redis key + DB 行；冒烟用 13800139999，脚本清理同策略

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `com.localink.api.dto.UserLoginDTO` | phone @Pattern + code @NotBlank/@Pattern(6位数字) |
| `BaseCode` | + SMS_CODE_INVALID(20002)、SMS_CODE_EXPIRED(20003) |
| `com.localink.constant.UserConstants` | 会话 key 前缀、TTL 1800s、Hash 字段名常量 |
| `com.localink.entity.User` | **首个实体**（@TableName("lk_user")，@TableId ASSIGN_ID） |
| `com.localink.mapper.UserMapper` | **首个 Mapper**（BaseMapper，@MapperScan 生效验证） |
| `UserService` / `UserServiceImpl` | GETDEL 消费 → 查/注册用户 → UUID token → Hash 会话 |
| `UserController` | POST /api/user/login |
| `UserLoginIntegrationTest` | @SpringBootTest ×4：首登注册+建会话、错码 20002 且码被消费、无码 20003、二次登录同用户新 token |
| `UserLoginValidationTest` | MockMvc ×2：缺 code / 码格式错 → 40001 |
| `docs/roadmap.md` | 勾选 M1.3（用户确认） |

## 4. 验证记录（2026-08-16 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 测试 | 同上 | **16/16 通过**（+4 登录集成 +2 登录校验） |
| 冒烟（脚本化） | `powershell -File smoke-m14.ps1` | 发码 0 → 日志取码 546957 → 登录 0 + 32 位 token → HGETALL 会话字段齐全（id=2088894966976757761 雪花、nickName=用户{id}）→ TTL 1799s → 错码 20002 → 清理 CLEANUP-OK 无残留 |

**排障记录**（本次迭代踩了三个真实的坑）：
1. **超长单行命令整体无输出**：一条 ~20 语句的复合命令执行后零输出、日志文件未创建、进程未拉起——命令在进入 PowerShell 前就失败了。对照实验排除正则反斜杠（`\d`）与反引号引号两种假设；中等长度命令均正常。结论：超长单行复合命令在本工具链中不可靠 → **多步冒烟序列一律写 .ps1 文件执行**
2. **.ps1 中 `-d "{\"phone\":...}"` 报 40001 请求体格式错误**：PowerShell 5.1 向原生程序传参会重组引号（PS 7.3 起 `$PSNativeCommandArgumentPassing` 才修复），内联双引号 JSON 被损毁。此前内联命令中单引号 JSON 能用是因为经过了工具载荷解码层 → **请求体改走 `ConvertTo-Json | Set-Content` + `curl --data @file`，零引号嵌套**
3. M1.2 两次"无输出"复盘归因：轮询循环最坏时长（30×4s）撞上工具超时上限，被终止时缓冲输出整体丢弃且子进程变孤儿——这是当时"假卡死"的机制；也是本轮把冒烟收敛为脚本文件 + 显式残留检查的原因

## 5. 学习清单

**核心知识点**
1. GETDEL 的原子语义：取回+删除单命令完成；单 key 原子用原生命令，多 key 原子才上 Lua（M3 预告）
2. UUID token vs JWT 的会话选型：服务端会话（Redis）可控、可踢、可刷新；JWT 无状态但失控——登录态场景选前者，开放 API 场景选后者
3. 分布式会话的动机：多实例部署时本地内存会话不共享，Redis 会话任意实例可校验；M1.5 拦截器依赖此
4. MP ASSIGN_ID 机制：IdentifierGenerator 在 insert SQL 执行前生成并回填实体 id；因此"依赖 id 的字段"只能 insert 后 update
5. Redis Hash 读写注意：HGETALL 返回无序字段对；Hash 无 HSET+EXPIRE 复合命令，MULTI/EXEC 或 Lua 可原子化

**面试必问题**
1. "登录态怎么做的？"——UUID token + Redis Hash 会话（TTL 30min），M1.5 双拦截器做刷新与鉴权；选服务端会话而非 JWT 是为了可控可踢
2. "验证码为什么用 GETDEL？"——取回即销毁，防验证码重放；GET+DEL 两步在并发下可被同码双登录
3. "用户不存在怎么处理？"——自动注册（手机号即账号），昵称默认"用户{id}"；MP 雪花 id 在 insert 时回填实体
4. "Redis 宕机登录会怎样？"——fail-fast 500；会话是强依赖，降级无意义；可用性靠 Redis 高可用部署而非业务降级

## 6. 下一步

M1.5 双拦截器鉴权：TokenRefreshInterceptor（有 token 则刷新 TTL + 写 UserHolder，不拦截）→ LoginInterceptor（无用户则 401）+ WebMvcConfigurer 注册顺序 + UserHolder（ThreadLocal）。
