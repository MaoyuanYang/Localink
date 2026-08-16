# 迭代任务卡：M1.3 发送短信验证码

| 字段 | 内容 |
|---|---|
| 迭代编号 | M1.3 |
| 分支 | `feature/m1-3-sms-code` |
| 状态 | 待用户确认 |

---

## 1. 目标

落地登录链路的第一半：`POST /api/sms/code` 发送短信验证码——手机号校验 → 防刷检查 → 生成 6 位码 → Redis 存储（TTL 120s）→ 日志模拟发送（PRD F-ACC-01：模拟短信，验证码写日志）。Redis 首次登场。

涉及表：无；涉及接口：`POST /api/sms/code`；涉及 Redis Key：`lk:sms:code:{phone}`（String，TTL 120s）。

## 2. 设计取舍

- **StringRedisTemplate 裸用，不建配置类**：starter 自动装配，key/value 全 String 序列化正合架构约定（architecture.md §5）。不提前封装 RedisCache——先裸用感受痛点（key 字符串散落、TTL 参数重复传递），M2.1 框架化才有真实需求驱动，这是演进式开发的刻意留白
- **Key 用临时常量 `SmsConstants.CODE_KEY_PREFIX`，不进 KeyManage**：Key 治理组件是 M2.2 的产出，现在建等于提前消耗 M2.2 的演进内容。**技术债显式登记**：M2.2 将把 `lk:sms:code:` 等散落常量重构进 KeyManage 枚举 + KeyBuild，届时本类删除。与 AGENTS.md §6"Key 一律走治理组件"的冲突以"组件尚未诞生"为由豁免，M2.2 后不再豁免
- **防刷用"key 存在即拒"，不做滑动窗口**：验证码 TTL 内一次有效已足够防刷；频率限流（IP/用户双维度）是 M3.13 限流框架的职责，现在做属于越界设计。代价：存在性检查（hasKey）与写入（set）非原子，并发两次请求理论上可同时通过——M1 单用户演示场景接受，M3 秒杀链路会用 Lua 展示原子化做法
- **SecureRandom 而非 RandomUtil/Math.random**：验证码是安全凭证，ThreadLocalRandom/Math.random 输出可预测（种子可推导）；SecureRandom 密码学安全。面试细节点
- **`set(key, code, Duration)` 一条命令**：SET 值与 EXPIRE 原子完成；分两步（set 后 expire）存在崩溃窗口——值已写入但 TTL 未设，key 永久驻留
- **DTO 放 api-model（`com.localink.api.dto`），模块首次启用**：DTO/VO 统一归 api-model 是架构既定划分；包名用 `com.localink.api.*` 避免与 server 裂包混淆。计划中"server 加 validation starter"一项**实际未执行**：api-model 在 M0.4 骨架时已声明 spring-boot-starter-validation 并传递给 server，重复声明无收益
- **Service 用接口+实现（SmsService/SmsServiceImpl）**：与后续 CRUD 服务统一风格；当前单方法看似多余，M1.4 起接口会快速丰满

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `localink-server/pom.xml` | + spring-boot-starter-data-redis |
| `application.yml` | + spring.data.redis（localhost:6379，对齐 docker-compose） |
| `com.localink.api.dto.SmsCodeSendDTO` | @NotBlank + @Pattern（手机号正则），api-model 首个类 |
| `com.localink.common.code.BaseCode` | + SMS_SEND_TOO_FREQUENT(20001)，用户域 2xxxx 首个错误码 |
| `com.localink.constant.SmsConstants` | Key 前缀 + TTL + 码长（临时方案，M2.2 重构） |
| `com.localink.service.SmsService` / `impl.SmsServiceImpl` | 防刷检查 + SecureRandom 生成 + SET EX 存储 + 日志模拟发送 |
| `com.localink.controller.SmsController` | POST /api/sms/code，@Validated @RequestBody |
| `SmsCodeIntegrationTest` | @SpringBootTest 真实 Redis ×2：发码后 key 存在/6位数字/TTL≤120；重发抛 20001 |
| `SmsControllerValidationTest` | MockMvc ×2：非法/空手机号 → 40001（M0.5 BindException 分支首次实战） |

## 4. 验证记录（2026-08-16 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件就绪 | `docker compose up -d mysql redis` | 双 healthy（@SpringBootTest 全上下文两者都需要） |
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 测试 | 同上 | 10/10 通过（异常处理器 3 + 数据源 3 + Redis 集成 2 + 校验 2） |
| 发码 | `curl -X POST /api/sms/code -d '{"phone":"13800139001"}'` | `{"code":0,"message":"成功"}`，服务端日志出现 `模拟发送短信验证码: phone=13800139001, code=533927` |
| 防刷 | 同手机号立即重发 | `{"code":20001,"message":"请勿频繁获取验证码"}` |
| 参数校验 | `{"phone":"12345"}` | `{"code":40001,"message":"phone 手机号格式不正确"}` |
| 清理 | `redis-cli DEL lk:sms:code:13800139001` | 返回 1，测试痕迹清除 |

**排障记录**：
1. `getExpire(key)` 返回 Long（秒）而非 Duration，测试编译失败 → 按 Long 断言修正（Spring Data Redis 3.x 的 getExpire 无 Duration 重载，只有 TimeUnit 重载）
2. 冒烟阶段一次"卡住"误报：实为启动命令序列被打断，服务本身已正常启动（日志 Started + 端口监听 + ping 通）；排查路径 = 进程列表 → netstat 端口 → 日志 tail → curl 实测，四步定位

## 5. 学习清单

**核心知识点**
1. StringRedisTemplate 零配置原理：RedisAutoConfiguration 按条件装配，String 序列化器是默认；何时才需要自定义 RedisTemplate（value 为对象且不用 String 序列化时）
2. `SET key value EX seconds` 的原子性：写值+TTL 一条命令 vs 两步操作的崩溃窗口
3. hasKey→set 的检查-执行竞态：非原子检查在并发下的漏洞，以及 Lua/SETNX 的原子化思路（M3 预演）
4. @Validated @RequestBody 生效链路：RequestResponseBodyMethodProcessor → WebDataBinder → Hibernate Validator → BindException → @RestControllerAdvice
5. 随机数安全分级：Math.random/ThreadLocalRandom（统计随机，可预测）vs SecureRandom（密码学随机）——安全凭证必须后者

**面试必问题**
1. "验证码接口怎么防刷？"——分层：TTL 内存在即拒（本迭代）→ IP/用户限流（M3.13）→ 前置令牌/图形验证码；单层不够，纵深防御
2. "Redis 宕机时发验证码会怎样？"——fail-fast 抛异常，用户看到系统繁忙；验证码是强依赖场景，不做降级（降级=无法登录，无意义）；这正是 M2 缓存体系要解决的可用性问题域
3. "为什么不用 Math.random 生成验证码？"——伪随机序列可由已知输出反推种子，攻击者可预测后续验证码；SecureRandom 熵源不可预测
4. "参数校验注解是怎么生效的？"——见知识点 4；追问点：@Valid 与 @Validated 区别（分组校验、方法参数校验）

## 6. 下一步

M1.4 登录/注册：验证码校验 + 用户不存在即注册（lk_user 实体与 Mapper 首次落地）+ UUID token + Redis Hash 会话（`lk:user:token:{token}`），接口 `POST /api/user/login`。
