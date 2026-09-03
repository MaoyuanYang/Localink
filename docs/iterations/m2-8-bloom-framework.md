# 迭代任务卡：M2.8 布隆过滤器框架：Redisson RBloomFilter 封装 + 配置化注册

| 字段 | 内容 |
|---|---|
| 迭代编号 | M2.8 |
| 分支 | `feature/m2-8-bloom-framework` |
| 状态 | 已实现（PR #40 待合入，2026-09-03；合入并学习确认后勾选 roadmap） |

---

## 1. 目标

cache-starter 补齐 architecture.md 预告的"布隆过滤器（Redisson）"职责，为 M2.9 业务接入提供框架能力：**Redisson RBloomFilter 的门面封装 + yml 配置化注册**。本迭代纯框架层，不碰任何业务代码、不改读写链路——业务接入（启动灌数据 + 前置拦截）留给 M2.9。同时完成 **Redisson 在本项目的首次落地**：引入依赖、构建 RedissonClient Bean，为 M3.3 lock-starter（RedissonClient 封装、分布式锁）铺路。

涉及表：无；涉及接口：无（对外行为零变化）；涉及 Redis Key：框架不定义业务 key，key 模板由宿主在 yml 配置（`localink.cache.bloom.filters.<别名>.key-template`），经 KeyBuilder 统一加 `lk:` 前缀。

## 2. 设计取舍

- **Redisson 引入方式：裸 `org.redisson:redisson` artifact + 自建条件化 RedissonClient Bean**。复用宿主已有的 `spring.data.redis` 连接参数（RedisProperties）以单机模式构建，`@ConditionalOnClass(RedissonClient.class)` + `@ConditionalOnMissingBean`——宿主自建或未来 lock-starter 接管时可整体覆盖。备选否决：`redisson-spring-boot-starter`——其 RedissonAutoConfiguration 会接管 RedisConnectionFactory/RedisTemplate，把 M2.1~M2.7 赖以生存的 Lettuce 栈整体替换掉，104 个存量测试暴露在不可控的行为漂移里；裸客户端 15 行配置代码换来零侵入。已知副作用（接受）：Redisson.create 启动即建连，应用启动从"惰性用 Redis"变为"强依赖 Redis 可用"——对依赖 docker compose 编排的本项目反而是启动期 fail-fast
- **注册机制：`BloomFilterRegistry` 单例持 `Map<别名, RBloomFilter<String>>`，构造期按配置逐个初始化**。"配置化注册"体现在：yml 里配几个过滤器，注册表里就初始化几个，业务方以别名读写。备选否决：hmdp 参考项目的 `BeanDefinitionRegistryPostProcessor + PriorityOrdered` 动态注册 Bean——为"每个过滤器一个 Spring Bean"引入容器级扩展点，复杂度与收益不成比例；本项目 starter 既有风格是 `@Bean + @ConditionalOnMissingBean` 普通装配，单例注册表足以承载（过滤器数量个位数、无运行期增删需求）
- **Key 治理合规：框架不硬编码任何 key**。过滤器 Redis key = KeyBuilder.build(宿主配置的 key-template)，与业务 key 同走 `lk:` 前缀治理；M2.9 会在 KeyManage 登记镜像枚举并用契约测试锁住，防止 yml 与治理登记处两处漂移
- **构造期快速失败（fail-fast）**：key-template 缺失、或 Redis 中已存在同名过滤器但参数不一致（tryInit 返回 false，说明 expectedInsertions/falseProbability 被改过）→ 启动直接抛 `LocalinkException(SYSTEM_ERROR)`。参数不一致的布隆过滤器继续跑会导致误判率失控，宁可启动失败也不带病运行——这也是"演进式"口味：把配置错误暴露在最早的时点
- **默认参数：expectedInsertions=10000、falseProbability=0.01**。1% 误判率是工业常规起点；预期插入量给保守默认值，实际过滤器在 yml 里按业务规模覆盖（M2.9 商户给 100000）。位数组内存 ≈ -n·ln(p)/(ln2)² / 8 字节，10 万条 1% 误判约 0.12MB，Redis 侧成本可忽略

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `pom.xml`（根） | dependencyManagement 新增 `org.redisson:redisson:${redisson.version}`（3.52.0 属性已有；starter 版声明保留给 M3.3 评估） |
| `localink-cache-starter/pom.xml` | 声明 `redisson` 依赖（版本走父管理） |
| `cache/config/CacheProperties.java` | 首个嵌套配置结构：`bloom.filters: Map<别名, Filter{keyTemplate, expectedInsertions=10000, falseProbability=0.01}>` |
| `cache/BloomFilterRegistry.java`（新增） | 门面：`add(别名, value)` / `contains(别名, value)`（false 一定不存在，true 可能存在）；未知别名抛 LocalinkException(SYSTEM_ERROR)；构造期 getBloomFilter + tryInit，缺模板/参数冲突快速失败 |
| `cache/config/CacheAutoConfiguration.java` | +2 Bean：`redissonClient`（destroyMethod=shutdown，@ConditionalOnBean(RedisProperties) 保证宿主有 Redis 配置）、`bloomFilterRegistry`（@ConditionalOnBean(RedissonClient)——无 Redisson 时注册表不装配，业务方注入即失败暴露） |
| `test/.../TestKeys.java` | +BLOOM("test:m28:bloom") 测试模板 |
| `test/resources/application.yml` | + demo 过滤器配置（expected-insertions: 1000, false-probability: 0.03，顺带验证参数可配） |
| `test/.../BloomFilterRegistryIntegrationTest.java`（新增，4 测试） | 装配冒烟（Bean 存在 + 完整 key 契约 `lk:test:m28:bloom`）/ add→contains 真、未加 false / 经带前缀 key 直连 RBloomFilter 可见（证明操作的就是治理后 key）/ 未知别名抛错 |
| `test/.../BloomFilterRegistryTest.java`（新增，纯单测） | 空 key-template 构造期快速失败（不依赖 Redis） |

过滤器生命周期：随应用上下文构造（tryInit 幂等：Redis 已有同名同参实例返回 true），随 RedissonClient shutdown 释放连接；位图与 config 哈希持久在 Redis（key + `{key}:config`），应用重启后 tryInit 直接命中已有实例，**灌入的数据跨重启保留**——这正是 M2.9"启动灌数据"的价值前提：日常重启无需全量重灌也能工作，重灌只是兜底对账。

## 4. 验证记录（2026-09-03 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy |
| 全量构建 | `./mvnw clean package` | BUILD SUCCESS，12 模块 |
| 全量测试 | 同上 | **109/109 通过**（cache-starter 39 = 原 34 + 新增 5；server 70 存量零回归） |
| 定向明细 | BloomFilterRegistryIntegrationTest | 4/4：装配 + key 契约 / add-contains 语义 / 前缀 key 可见性 / 未知别名抛错 |
| 定向明细 | BloomFilterRegistryTest | 1/1：空 key-template 构造快速失败 |
| 存量回归 | RedisCacheIntegrationTest 28/28、ShopCacheIntegrationTest 10/10 等 | 全过——RedissonClient Bean 落入 server 上下文后，104 个存量测试无一受影响 |
| 启动冒烟 | `powershell -File scripts/smoke.ps1` | PING-OK: pong + CLEANUP-OK |

### 排障记录

- **`@AfterEach` 删布隆 key 导致后续测试报 "Bloom filter config has been changed"**：首版集成测试每个用例后 `RBloomFilter.delete()` 清理，下一个用例 `add()` 即抛 Redis 脚本断言错误。原因：delete 清掉了 Redis 侧 config 哈希，但注册表持有的 RBloomFilter 实例仍缓存着初始化状态直接调 add，Lua 脚本校验 Redis 里的 size/hashIterations 与参数一致时失败。教训：**Redisson RBloomFilter 实例与 Redis 侧结构是"一次性绑定"，运行期删除结构会让实例失效**（生产语义也如此——没人会在运行期删布隆）。修复：清理改为 `@AfterAll` + `@TestInstance(PER_CLASS)` 类级一次；用例间以 nanoTime 唯一值互不干扰

## 5. 学习清单

**核心知识点**
1. 布隆过滤器原理：m 位位数组 + k 个哈希函数；写入把 k 个位置置 1，查询 k 个位置全 1 才返回"可能存在"——**false 一定不存在（无漏报），true 可能误判（有误报）**。误判率 p ≈ (1 - e^(-kn/m))^k，最优 k = (m/n)ln2；内存 m ≈ -n·ln(p)/(ln2)² 位。10 万元素 1% 误判 ≈ 0.12MB，成本极低
2. 为什么适合防缓存穿透：不存在的一定不存在——布隆说没有的 id 根本不必进缓存/DB；误判的代价只是"多走一次常规缓存+DB 查询"，退化为无布隆行为，不放大故障
3. 布隆为什么不能删除：位是多个元素共享的，删一个元素的位会连带破坏其他元素的判断。衍生方案：计数布隆（每位换成计数器，代价大）、布谷鸟过滤器（支持删除，Redisson 未内置）。工程替代：删除场景靠下游兜底（本项目：删除商户靠空值缓存拦截，M2.9 落地）
4. Redisson RBloomFilter：位图存 Redis（String 位图 + `{key}:config` 哈希存参数），天然分布式、跨实例共享、重启不丢；`tryInit(expectedInsertions, falseProbability)` 计算并固化 m/k，参数与已存实例不一致返回 false——所以参数调整必须先删旧实例（本框架选择启动快速失败而非静默容忍）
5. 裸客户端 vs spring-boot-starter 的接入权衡：starter 的自动配置会替换 RedisTemplate/ConnectionFactory（Lettuce→Redisson），对已有重度依赖 StringRedisTemplate 的系统是全局行为变更；裸客户端 + 自建单例把变更面收窄到"新增一个 Bean"
6. 配置化注册的实现档次：BeanDefinitionRegistryPostProcessor 动态注册（每过滤器一个 Bean，容器级扩展，hmdp 方案）vs 单例注册表持 Map（一次装配全部持有，本项目方案）——过滤器无运行期增删、无按 Bean 生命周期管理的需求时，后者复杂度低一个量级
7. fail-fast 在框架层的价值：key 缺失、参数冲突、未知别名，三类错误全部在"启动时/首次调用时"抛 SYSTEM_ERROR 而非静默降级——布隆静默失效意味着穿透防护悄悄消失，比启动失败危险得多
8. Key 治理在框架层的贯彻方式：框架自身不定义业务 key，key-template 由宿主 yml 提供，经 KeyBuilder 加统一前缀——与业务 KeyManage 枚举同一套治理规则，测试环境改 `lk:` 前缀时布隆 key 自动跟随

**面试必题**
1. "布隆过滤器为什么会误判？误判率怎么控制？"——哈希碰撞导致不同元素共享位；误判率由 m/n（位数组大小/元素数）和 k（哈希次数）决定，给定预期元素量和目标误判率可解析求出最优参数（Redisson tryInit 就是干这个）
2. "布隆过滤器说存在就一定存在吗？"——不一定（可能误判）；说不存在就一定不存在（无漏报，删除不会导致漏报）。单向确定性决定了它只能做"前置粗筛"，不能做存在性权威
3. "布隆怎么应对删除？"——标准布隆不支持；计数布隆/布谷鸟过滤器支持但代价/复杂度上升；工程上更常见的是接受误判 + 下游兜底（本项目：空值缓存拦"曾经存在"）
4. "Redisson 的布隆和 Guava 的有什么区别？"——Guava 在 JVM 内（单实例内存，重启丢失、多实例不共享）；Redisson RBloomFilter 位图在 Redis（分布式共享、AOF 持久化跨重启），代价是每次判断一次网络往返——所以它适合放在缓存之前做粗筛，而不是高频小粒度判断
5. "布隆过滤器的数据怎么初始化和更新？"——启动全量灌入（本项目 M2.9：ApplicationRunner 全表扫描）+ 写链路同步 add（新增即入）；重启后 Redis 里的位图还在，重灌只是对账兜底。存量数据变更（参数调整/数据清洗）需要删掉重建
6. "你的框架怎么保证布隆 key 不散落硬编码？"——key-template 集中在 yml 配置 + KeyBuild/KeyBuilder 统一前缀，业务侧 KeyManage 登记镜像模板并有契约测试锁字符串——key 治理是框架设计问题而不只是编码规范问题

## 6. 下一步

M2.9 布隆过滤器接入：启动初始化灌数据（ApplicationRunner 全量灌入 lk_shop id + create 落库同步 add）+ `detail()` 查询链路前置拦截（布隆判不存在直接 NOT_FOUND，不进缓存不进 DB）——与 M2.4 空值缓存形成"布隆拦从未存在、空值拦曾经存在"的两层防线，根治 M2.4 遗留的"随机 id 扫射打爆 Redis 内存"缺陷。
