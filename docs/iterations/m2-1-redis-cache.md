# 迭代任务卡：M2.1 Redis 工具框架① RedisCache 封装

| 字段 | 内容 |
|---|---|
| 迭代编号 | M2.1 |
| 分支 | `feature/m2-1-redis-cache` |
| 状态 | 实现完成，PR 待审 |

---

## 1. 目标

M2 开篇：在 `localink-cache-starter` 落地 RedisCache 门面——**分组子门面**（入口接口 + String/Hash/Set/ZSet 四个分组操作接口），统一序列化（String 原样、对象 fastjson2 JSON 化）、统一 TTL（java.time.Duration），并以 Spring Boot 3 `AutoConfiguration.imports` 完成项目**首个 starter 自动装配**。偿还 M1.3 登记的"裸用 StringRedisTemplate"债务的 API 侧一半；Key 治理（另一半）留给 M2.2。

涉及表：无；涉及接口：无（纯框架迭代）；涉及 Redis Key：测试用 `lk:test:*` 前缀。

## 2. 设计取舍

- **分组子门面 vs 单巨型接口**：hmdp-plus 的 RedisCache 是 100+ 方法的单接口；Localink 选 `RedisCache` 入口（key 通用操作 + 分组访问器）+ `RedisStringOps/RedisHashOps/RedisSetOps/RedisZSetOps` 四个分组接口。理由：①职责清晰，调用方 `redisCache.strings().set(...)` 语义自解释；②未来加 List/Geo/BitMap/HyperLogLog 只增分组不动主接口；③与 RedisTemplate 自身 opsForXxx 设计同构，面试可对比讲。代价是多一层调用
- **最小 API 集（需求驱动）**：只封装 M2~M6 路线图已确定用到的操作（如 setIfAbsent→M2.6 互斥锁/M3.10 幂等标记、Set intersect→M6.5 共同关注、ZSet incrementScore/reverseRangeByScore→M6.4 点赞榜/M6.7 Feed 滚动分页）。不做 hmdp 式"全家桶"——方法越多维护面越大，演进式开发允许需求出现时再加分组/方法
- **不做 List/Geo/BitMap/HyperLogLog**：roadmap 明确 M2.1 范围为 String/Hash/Set/ZSet；BitMap（M6.17 签到）/GEO（M6.18 附近商户）/HyperLogLog（M6.12 UV）届时按需扩组
- **key 参数用 String，M2.2 再换 KeyBuild**：Key 治理组件是 M2.2 产出，提前建等于消耗 M2.2 演进内容（m1-3 任务卡同款判据）。M2.2 将把方法签名统一切到 KeyBuild，**签名变更成本已显式接受**——演进叙事价值（先解决 API 散落，再解决 key 散落）大于一次重构
- **TTL 用 java.time.Duration**：类型安全无单位歧义，与 M1.3/M1.4 现有代码（Duration.ofSeconds）一致；hmdp 的 long+TimeUnit 属旧风格
- **序列化约定强制点**：写入时 String 原样、其余对象 fastjson2 JSON 化；读取时按目标 Class 反序列化（architecture.md §5 约定的首个落地载体）。Hash 的 entries 返回原始 `Map<String,String>`，与 M1.4 会话存储格式兼容
- **TTL 施加方式**：String 的 set 用 `SET key value EX ttl` 单命令原子写入；Hash/Set/ZSet 结构 Redis 无字段级 TTL，采用"写后 expire"两步（与 M1.4 saveSession 现状一致，会话/订阅场景可接受）
- **不迁移存量业务代码**：SmsServiceImpl/UserServiceImpl/TokenRefreshInterceptor 三处裸用留到 M2.2 随 Key 治理一起切换，避免本迭代迁移、下迭代因换 KeyBuild 二次返工（最小迭代铁律）
- **框架层不吞异常**：Redis 操作异常直接上抛（fail-fast），不包装不降级——缓存框架的正确性优先于可用性，降级策略是上层业务（M2.3+ 缓存重建）的职责
- **自动装配首秀**：`CacheAutoConfiguration` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（architecture.md §2.3 约定），`@ConditionalOnBean(StringRedisTemplate.class)` 保证宿主未配 Redis 时不强行注册
- **server 引入依赖但不使用**：server pom 加 cache-starter 依赖 + 一个装配冒烟测试，验证"零配置引入"在真实应用生效（M0.4 骨架验证同款做法）；业务使用从 M2.2/M2.3 开始

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `RedisCache`（接口） | 入口：hasKey/delete(单/批)/expire/getExpire + strings()/hashes()/sets()/zsets() |
| `RedisStringOps` | set(±ttl)/setIfAbsent/get(key,Class)/getString/getList/getAndDelete/increment |
| `RedisHashOps` | put/putAll(±ttl)/get(key,field,Class)/entries/hasField/delete/increment |
| `RedisSetOps` | add/remove/isMember/members/intersect/size |
| `RedisZSetOps` | add(±ttl)/incrementScore/remove/size/rank/reverseRank/score/range/reverseRange/reverseRangeWithScore/reverseRangeByScore |
| `ZSetEntry<T>` | record(value, score)，带分数查询返回载体 |
| `RedisJsonCodec` | fastjson2 封装：写入序列化 + 按 Class/List 反序列化 |
| `Default*` 实现 ×5 | 基于 StringRedisTemplate |
| `CacheAutoConfiguration` + imports 文件 | 项目首个 starter 自动装配 |
| pom ×2 | cache-starter 加 data-redis/fastjson2/test；server 引入 cache-starter |
| 测试 | cache-starter `RedisCacheIntegrationTest`（真实 Redis，四组全覆盖）+ server `CacheAutoConfigSmokeTest` |

## 4. 验证记录（2026-08-18 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy |
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| cache-starter 测试 | 同上 | **27/27 通过**（String×8 / Hash×5 / Set×3 / ZSet×8 / 通用×2 / 装配×1） |
| server 测试 | 同上 | **54/54 通过**（原 53 + 新增 CacheAutoConfigSmokeTest×1） |
| 启动冒烟 | `java -jar localink-server-0.0.1-SNAPSHOT.jar` + `GET /ping` | 返回 pong，starter 引入不破坏应用 |

**排障记录**：冒烟后 `Stop-Process` 未杀掉后台 java 进程（进程残留占用），手动 `Stop-Process -Id <pid> -Force` 清理并确认 8086 端口释放。教训：冒烟脚本的进程清理需验证生效，后续冒烟沿用"启动→探活→杀进程→确认端口释放"四步。

## 5. 学习清单

**核心知识点**
1. 门面模式：RedisCache 统一入口收敛序列化/TTL/异常约定，调用方不再触碰 opsForValue/opsForHash 细节；分组子门面（strings/hashes/sets/zsets）与 RedisTemplate 自身 opsForXxx 设计同构，但接口按演进需要裁剪
2. Spring Boot 3 自动装配：`AutoConfiguration.imports` 由 ImportCandidates 加载 → @AutoConfiguration 条件过滤 → 注册 bean；条件依赖其他自动装配的 bean（StringRedisTemplate 来自 RedisAutoConfiguration）时必须 @AutoConfigureAfter 保证处理顺序，否则 @ConditionalOnBean 可能误判
3. 序列化约定：全 String 序列化 + fastjson2 JSON 化——JDK 序列化不可读/跨版本脆弱，GenericJackson2JsonRedisSerializer 嵌入 @class 类型信息与类名强耦合；String 方案人可读、可 redis-cli 直接排查
4. SET NX EX 原子性：setIfAbsent(key, value, ttl) 是单条命令——"先查再写"存在竞态，互斥锁/幂等标记/一人一单都依赖这条原子语义（M2.6/M3.5/M3.10 的地基）
5. 结构级 TTL 的两步写法：Redis 的 TTL 只作用于 key 而非字段，Hash/Set/ZSet 写入后 EXPIRE 非原子；会话/订阅场景可接受，若写后崩溃最坏产生无过期 key（可由对账/巡检兜底）

**面试必问题**
1. "为什么要封装 RedisTemplate？"——序列化约定强制统一、key/TTL 治理收敛、业务代码面向接口可 mock 测试、隐藏 opsForXxx 细节降低误用面
2. "序列化不一致会怎样？"——同一 key 不同序列化器写入的字节流互不可读，跨服务/跨版本读取直接报错或脏数据；所以序列化约定必须在门面层强制而非靠自觉
3. "starter 是怎么做到零配置生效的？"——jar 内 imports 文件声明自动配置类 → Spring Boot 启动时加载 → 条件注解决定是否注册 bean；对比旧式 spring.factories（3.x 已弃用该路径）
4. "setIfAbsent 为什么必须带 TTL？"——不带 TTL 的 NX 占位若持有者崩溃则永久占位（死锁）；TTL 是最小可用的自愈手段，M3.3 Redisson 锁的看门狗续期是它的进阶版

## 6. 下一步

M2.2 Key 治理：KeyManage 枚举 + KeyBuild 前缀规范 + RedisCache 签名切换 + 存量三处迁移（偿还 M1.3 登记的临时 Key 常量债，`SmsConstants`/`UserConstants` 中 key 前缀收敛）。
