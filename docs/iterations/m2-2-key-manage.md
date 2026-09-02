# 迭代任务卡：M2.2 Redis 工具框架② Key 治理（KeyManage + KeyBuild）

| 字段 | 内容 |
|---|---|
| 迭代编号 | M2.2 |
| 分支 | `feature/m2-2-key-manage` |
| 状态 | 已完成（PR #28 合入，2026-09-02 用户学习确认，roadmap 已勾选） |

---

## 1. 目标

偿还 M1.3 登记、M2.1 明确留给本迭代的 Key 治理债：cache-starter 落地 **KeyTemplate 接口 + KeyBuild 值对象 + KeyBuilder**（统一环境前缀，`localink.cache.key-prefix` 可配，默认 `lk:`），RedisCache 全量签名从 `String key` 切换为 `KeyBuild key`（M2.1 显式接受的签名变更成本本迭代兑现）；server 侧新增业务 **KeyManage 枚举**（模板 + 默认 TTL + 语义说明），完成存量三处迁移（SmsServiceImpl / UserServiceImpl / TokenRefreshInterceptor），删除临时常量类 `SmsConstants`、`UserConstants` 收敛为纯 Hash 字段名。此后 AGENTS.md §6"Key 一律走治理组件"不再豁免。

涉及表：无；涉及接口：无（纯框架 + 内部迁移迭代）；涉及 Redis Key：`lk:sms:code:{phone}`、`lk:user:token:{token}`（产出字符串与 M1 完全一致，短命数据免迁移）。

## 2. 设计取舍

- **KeyManage 放 server，框架层只做机制**（用户决策）：cache-starter 提供 KeyTemplate 扩展接口 + KeyBuild 值对象 + KeyBuilder bean，不含任何业务 key；业务 KeyManage 枚举放 server。理由：框架零业务耦合可复用，未来 lock/ratelimit/delay 各 starter 自带自己的 key 模板枚举。对比 hmdp-plus：其 RedisKeyManage（含全部业务 key）直接放 redis 框架模块，框架与业务耦合，Localink 不采纳
- **默认 TTL 进枚举**（用户决策）：枚举项 = 模板 + 默认 TTL + 语义说明，key 语义"一处可查"；兑现 m1-3"届时本类删除"承诺（SmsConstants 整类删除，CODE_LENGTH 随实现内聚为私有常量；UserConstants 只留 Hash 字段名）。无固定 TTL 的 key（如 M2.3 商户缓存）届时登记为无默认 TTL，M2.5 雪崩抖动时调用方显式传 TTL 覆盖默认值
- **强类型签名即治理**：RedisCache 全部 key 参数 `String → KeyBuild`，绕过治理组件无法通过编译——AGENTS.md §6 从"约定"升级为"编译期强制"。代价：M2.1 的 27 个集成测试与实现全量迁移签名（该成本 M2.1 任务卡已显式接受）
- **KeyBuild 构造器包私有**：只能经 KeyBuilder 产生，杜绝 `new KeyBuild("裸字符串")` 绕过前缀；equals/hashCode 保留（可作集合元素 / Map key）
- **前缀用冒号段而非 hmdp 的 `-` 拼接**：`lk:` + `sms:code:xxx` 延续 M1 既有 key 形态（redis-cli 按冒号分层浏览的习惯），与 hmdp-plus 的 `prefix-relKey` 风格区分；环境隔离通过配置改前缀（如 `lk:dev:`），不改代码
- **hash tag 原生支持**：模板直接写 `{%s}`（String.format 不处理大括号，原样保留），M3.6 秒杀同槽位直接可用，框架零特判
- **存量 key 字符串保持不变**：`lk:sms:code:` / `lk:user:token:` 产出与 M1 完全一致，验证码/会话均为短命数据，无迁移成本。server 新增契约测试锁定完整 key 字符串，防前缀漂移
- **测试同步去裸化**：server 集成测试中的 StringRedisTemplate 全部替换为 RedisCache + KeyBuilder，迁移后全仓（主代码 + 测试）grep 无业务裸用，治理闭环可验证

## 3. 产出物

**框架层（cache-starter）**

| 文件 | 说明 |
|---|---|
| `KeyTemplate` 接口 | `template()`；业务 KeyManage 及其他 starter 的 key 枚举实现它 |
| `KeyBuild` 值对象 | 完整 key 包装；包私有构造器，equals/hashCode |
| `KeyBuilder` | `build(KeyTemplate, args...)`：String.format + 前缀拼接 |
| `CacheProperties` | `localink.cache.key-prefix`，默认 `lk:` |
| `CacheAutoConfiguration` | 追加 KeyBuilder 注册 + @EnableConfigurationProperties |
| RedisCache + 四分组接口 + 五实现 | 签名 `String key` → `KeyBuild key`（含 `delete(Collection<KeyBuild>)`） |

**业务层（server）**

| 文件 | 说明 |
|---|---|
| `KeyManage` 枚举 | SMS_CODE / USER_TOKEN（模板 + 默认 TTL + 语义说明） |
| SmsServiceImpl / UserServiceImpl / TokenRefreshInterceptor | 裸 StringRedisTemplate → RedisCache + KeyBuilder |
| 删除 `SmsConstants`；`UserConstants` 收敛 | 只留 FIELD_*（Hash 字段名非 key，不属治理范围） |
| 测试 | 7 个集成测试迁移 + `KeyManageTest` 契约测试；cache-starter 新增 `KeyBuilderTest` + `TestKeys` fixture |

## 4. 验证记录（2026-08-28 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy（Docker Desktop 冷启动后 `docker compose up -d mysql redis` 拉起） |
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| cache-starter 测试 | 同上 | **34/34 通过**（RedisCache 集成 28：String×8 / Hash×5 / Set×3 / ZSet×8 / 通用×2 / 装配×2 + KeyBuilder 单测×6：前缀/多参数/hash tag/默认前缀/自定义前缀/equals） |
| server 测试 | 同上 | **58/58 通过**（原 54 + KeyManage 契约×3 + 装配冒烟 +1；8 个 Redis 相关集成测试全部改走治理链路） |
| 裸用清零 | 全仓 grep | `StringRedisTemplate` / `SmsConstants` / `TOKEN_KEY_PREFIX` / `CODE_KEY_PREFIX` 在 server 主代码与测试中零残留（仅框架测试保留 1 处用于裸验线格式） |
| 启动冒烟 | `powershell -File scripts/smoke.ps1` | PING-OK: pong + CLEANUP-OK（进程树已杀、8086 释放） |

**排障记录**：无（沿用 M2.1 固化的 smoke.ps1 脚本冒烟，未出现进程残留）。

## 5. 学习清单

**核心知识点**
1. Key 治理三件套：模板枚举（登记处，唯一新增点）→ KeyBuilder（前缀拼接，环境可配）→ KeyBuild（值对象，强类型流通）。治理目标：前缀统一、环境隔离、语义可查、禁止散落硬编码
2. 强类型约束：门面 API 参数从 String 换成值对象后，绕过治理无法通过编译——"约定优先"升级为"编译期强制"，比 code review 可靠且零成本执行
3. 值对象模式：KeyBuild 无行为仅承载身份，equals/hashCode 使其可作集合元素 / Map key；包私有构造器收口创建路径，是"治理唯一入口"的技术支撑
4. String.format 与 hash tag：`{%s}` 的大括号不是 format 占位符，原样保留——Redis Cluster 按 `{}` 内内容哈希分槽，同 hash tag 的 key 落同槽，才能用 Lua/MULTI 做多 key 原子操作（M3.6 秒杀的地基）
5. 前缀做环境隔离：`lk:dev:` / `lk:prod:` 共用 Redis 实例互不干扰；代价是 key 变长（内存略增），换来多环境共存与按前缀批量 SCAN/清理/监控的能力

**面试必问题**
1. "Redis key 为什么要治理？不治理会怎样？"——散落硬编码导致前缀不一致、无法按业务批量扫描/清理/监控、多环境共用实例互相污染；治理后 key 登记可查（枚举即文档）、前缀可配、变动单点修改
2. "为什么用枚举而不是常量类？"——枚举天然单例、可携带多字段元数据（模板/TTL/说明）、IDE 可穷举检索防漏改；常量类只有字符串无法挂元数据
3. "如何保证没人绕过治理组件直接拼字符串？"——门面 API 参数强类型化（String→KeyBuild），绕过即编译失败；辅助手段：code review + SCAN 巡检未登记前缀
4. "hash tag 是什么？什么时候用？"——`{xxx}` 让 Redis Cluster 把 key 固定到同一槽位；需要多 key 原子操作（Lua/事务）的场景用（如秒杀券+库存+已购集合），代价是该组 key 的热点集中单分片，不能滥用

## 6. 下一步

M2.3 商户缓存 V1：旁路缓存（先更库再删缓存）——首个通过 KeyManage 新登记业务 key（`cache:shop:*`）的迭代，Key 治理进入"只增枚举项"的常态。
