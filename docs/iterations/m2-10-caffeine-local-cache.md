# 迭代任务卡：M2.10 Caffeine 本地缓存：双层读链路

| 字段 | 内容 |
|---|---|
| 迭代编号 | M2.10 |
| 分支 | `feature/m2-10-caffeine-local-cache`（基于 main，M2.8/M2.9 已合入） |
| 状态 | 已完成（PR 待合入，2026-09-11 实施与验证完毕） |

---

## 1. 目标

M2.1~M2.9 建成的读链路（布隆 → Redis 逻辑过期 → DB）把 DB 压到极限防护，但**每个请求仍要付 1~2 次 Redis 网络往返**（布隆查询 + GET），Redis 本身成为单点热点与故障点——M2.5 学习清单预告的雪崩成因②（Redis 宕机，缓存层整体消失）至今无对策。本迭代在 Redis 之前插入**进程内 Caffeine 本地缓存（L1）**，形成"本地缓存（纳秒级、进程内）→ Redis（分布式一致）→ DB（事实源）"三级纵深：热点 key 在 L1 TTL 窗口内 0 次 Redis 往返，进一步削 Redis 热点流量；Redis 宕机时 L1 命中的热点请求仍可服务（成因②的兜底一环）。同时兑现 M2.7/M2.5 两个伏笔的台面评估（泛型 entry 序列化下沉、TTL 抖动下沉）。

涉及表：`lk_shop`（无变更）；涉及接口：`GET /api/shop/{id}`（对外行为不变，变化在读路径前置一层进程内查询与两处新鲜值回填）；涉及 Redis Key：**不新增**（L1 是进程内结构，无 key 概念，`localink.cache.local.caches.shop` 仅是 yml 配置项）。

## 2. 设计取舍

- **读链路重排为 L1 → 布隆 → Redis → DB，修正 M2.9 的"布隆最前"决策**：M2.9 把布隆放最前的理由是"先查缓存=白白多一次 Redis 往返"——当时"缓存"只有 Redis。L1 查询是进程内零网络成本，这个权衡被打破：L1 命中的请求连布隆查询都省掉（每请求省 2 次 Redis 往返）；更重要的是 **Redis 宕机时布隆查询本身会失败**，布隆在最前则 L1 兜底永远走不到——L1 提到最前，Redis 宕机场景下热点 key 仍能返回（M2.5 成因②对策的实证前提）。代价：L1 中残留的已删商户在 TTL 窗口内可能被返回——本实例 delete() 会即时失效 L1，窗口仅存在于"他实例残留"（M3.9 广播的课题），单实例部署下为零。穿透防线不弱化：随机 id 不在 L1（L1 只存验证过的新鲜正缓存），miss 后照旧被布隆拦截
- **L1 存解析后的 ShopVO（对象引用），不存 LogicalExpiryEntry、不序列化**：Caffeine 值是进程内对象引用，读写零序列化成本（对比 Redis 每次都要 fastjson2 编解码）。存 entry 则 L1 命中路径还要判 expireTime——把 M2.7 的逻辑过期语义复制进 L1 徒增复杂度；L1 用自己的 `expireAfterWrite` 短 TTL 独立管理生命周期，两层各管各的过期，互不耦合
- **只在两处"新鲜值"产生点回填 L1，逻辑过期旧值与空值都不进**：① Redis entry 未过期分支；② `loadAndCacheLogical` 查库成功后。读到逻辑过期旧值的请求**不回填**——旧值进 L1 会把"异步重建毫秒级完成"的脏值窗口人为拉长到 L1 TTL（10s），且重建完成后 L1 感知不到；不回填则过期窗口内每个请求照旧走 Redis（回到 M2.10 之前的行为，受逻辑过期保护），重建完成后恢复回填。空值/NOT_FOUND 不进 L1：同 M2.7"正缓存有限集合、空值无限集合"原则，且布隆+Redis 空值标记已两层拦截，L1 无需参与穿透防护
- **L1 TTL 定 10s + maximumSize 1000（yml 可配）**：10s 窗口内热 key 0 次 Redis 流量，削流效果在实验中观测清晰；本实例 update/delete 即时失效，跨实例脏窗口 10s（多实例广播是 M3.9）。1s 太保守（只有超高频热点有削流感），60s 对"商户详情"这类可编辑内容的脏窗口偏激进。1000 容量对"热点商户子集"绰绰有余（全表也才 10 万级），W-TinyLFU 淘汰频率高的 key，冷 key 自然让位
- **框架形态对照 BloomFilterRegistry 范式：cache-starter 提供 LocalCacheRegistry，yml 配置驱动**：`localink.cache.local.caches.<alias>` 声明式建缓存（maximum-size / expire-after-write），业务方以别名取类型化视图 `LocalCache<K,V>`。Caffeine 泛型运行时擦除的 unchecked cast 收敛在框架实现内部，业务侧零 Caffeine 类型依赖。薄接口只给 getIfPresent/put/invalidate 三方法——不给 `get(key, loader)` 加载重载：L1 miss 的并发回源由 Redis 层自身防护（互斥锁/逻辑过期），L1 无需再抗击穿，显式 get/put 让"何处回填"在调用方一目了然
- **M2.7 伏笔评估（泛型 entry 序列化下沉框架层）：结论=维持决策继续延迟**。评估发现 L1 存进程内对象引用、不经过任何序列化，**并未产生第二个 `TypeReference<LogicalExpiryEntry<T>>` 场景**——"第二个泛型缓存场景"（如 M3 秒杀券详情走 Redis 逻辑过期）出现之前，给 `RedisJsonCodec` 扩 Type API 没有第二个消费者，依旧违背最小迭代。M2.9 任务卡预告的"拿到台面评估"至此闭环：答案是"评估了，L1 不触发，继续等真正的第二个 Redis 泛型场景"
- **M2.5 伏笔评估（TTL 抖动下沉 cache-starter）：结论=维持决策不下沉**。L1 的过期只会让请求回落到 Redis 层（受逻辑过期/互斥保护），**不会砸 DB**——抖动防御的目标（DB 压力峰值）不存在于 L1 过期路径；Redis 写入侧的抖动场景（正缓存/空值 TTL）依旧只有商户缓存一处调用方。`RedisStringOps.set` 的 jitter 重载继续等第三个抖动场景
- **update()/delete() 同步失效 L1（先更库 → 删 Redis → 失效 L1）**：单实例即时一致。多实例下他实例 L1 残留最长 10s——这是**已知的、有界的**不一致窗口，根治方案是 M3.9 Kafka 失效广播，本迭代记录而非解决（最小迭代）。备选否决：update 后主动回填 L1 新值（先查库回填再删 Redis）——引入"读旧库值回填"竞态，得不偿失；失效（invalidate）永远比更新（update）安全，这是缓存失效的经典原则

## 3. 产出物

| 文件 | 说明 |
|---|---|
| 父 `pom.xml` | properties + dependencyManagement 显式登记 caffeine（3.2.2，与 Boot 3.5.4 BOM 管理版本一致） |
| `localink-cache-starter/pom.xml` | + caffeine 依赖 |
| `cache/config/CacheProperties.java` | + `Local` 嵌套配置：`localink.cache.local.caches` 为 alias→spec 映射（maximumSize 默认 1000、expireAfterWrite 默认 10s） |
| `cache/LocalCache.java`（新增） | 薄接口 `<K,V>`：getIfPresent / put / invalidate |
| `cache/LocalCacheRegistry.java`（新增） | 构造时按配置为每个别名建 Caffeine 实例，`cache(alias)` 返回类型化视图；未注册别名抛 LocalinkException（对照 BloomFilterRegistry 的 resolve 语义） |
| `cache/config/CacheAutoConfiguration.java` | + LocalCacheRegistry Bean（@ConditionalOnMissingBean，不依赖 Redis/Redisson） |
| `localink-server/application.yml` | + `localink.cache.local.caches.shop`（maximum-size: 1000, expire-after-write: 10s） |
| `constant/LocalCacheAlias.java`（新增） | 本地缓存别名常量 `SHOP = "shop"`，与 yml map key 对应（对照 BloomFilterAlias） |
| `config/LocalCacheConfig.java`（新增） | `shopLocalCache` Bean：registry.cache(SHOP) → `LocalCache<String, ShopVO>`，供 Service 构造注入 |
| `service/impl/ShopServiceImpl.java` | `detail()` 重排：首行 L1 查询命中直返 → 布隆 → Redis → DB；fresh entry 分支与 `loadAndCacheLogical` 回填 L1；`update()`/`delete()` 同步失效 L1 |
| `cache-starter/test/.../LocalCacheRegistryTest.java`（新增） | 单测：配置建缓存、未注册别名异常、expireAfterWrite 过期淘汰、maximumSize 容量淘汰 |
| `server/test/.../ShopCacheIntegrationTest.java` | **新增 4**：L1 命中二次 detail 不再访问 Redis（spy RedisCache 计数）、expired 旧值不进 L1（每次仍查 Redis）、update/delete 失效 L1、L1 TTL 过期回源；**存量语义迁移排查**（断言绑定"二次 detail 查 Redis/DB"的测试因 L1 命中而迁移，同 M2.9 空值场景迁移先例） |

读链路时序：`GET /api/shop/{id}` → **L1 Caffeine getIfPresent**：命中 → 直返（0 次 Redis，纳秒级）→ miss → **布隆 contains**：false → NOT_FOUND（防线①）→ true → `GET lk:shop:info:{id}` → null → 互斥锁同步重建（防线②回退）→ 空串 → NOT_FOUND → entry 未过期 → **回填 L1** + 直返 → entry 过期 → 异步重建 + 旧值直返（**不进 L1**）。

写链路时序：`POST /api/shop` 落库 + 布隆 add；`PUT /api/shop` 先更库 → 删 Redis → **失效 L1**；`DELETE /api/shop/{id}` 先删库 → 删 Redis → **失效 L1**。

## 4. 验证记录（2026-09-11 本机实测）

### 4.1 常规验证

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy（实验②中途会临时停 redis，结束时已恢复） |
| 全量构建 | `./mvnw clean package` | BUILD SUCCESS，12 模块 |
| 全量测试 | 同上 | **124/124 通过**（cache-starter 46 = M2.9 的 41 + LocalCacheRegistryTest 5；server 78 = M2.9 的 74 + ShopCache 新增 3 + ShopLocalCacheExpiry 1） |
| 定向明细 | LocalCacheRegistryTest | 5/5：配置建缓存与类型化视图 / 未注册别名快速失败 / expireAfterWrite 过期淘汰 / maximumSize 容量上界（cleanUp 后断言）/ null 写入忽略 + invalidate |
| 定向明细 | ShopCacheIntegrationTest | 16/16：新增 3（L1 命中 0 次 Redis 门面调用且 0 次布隆查询 / 过期旧值不回填 L1（argThat 锁"stale-"值从未 put）/ update+delete 双失效 L1）+ 改造 2（两个逻辑过期场景在 expireEntryNow 后同步 expireLocalCacheNow，模拟 L1 TTL 已过——否则 L1 命中导致异步重建永不触发）+ 存量 11 全过 |
| 定向明细 | ShopLocalCacheExpiryIntegrationTest | 1/1：独立上下文（expire-after-write=1s），L1 命中 0 次 Redis → sleep 1.5s → 回源 Redis 恰 1 次 strings() 并重新回填 L1 |
| 存量回归 | ShopCrud / ShopAuth / Seckill / Voucher / User / Sms 等 | 全过 |

### 4.2 实验①：削流对比（同热度协议：预热 3 轮 + 第 4 轮测量，对照 M2.7 协议）

流程（两 jar 完全对称）：`CONFIG RESETSTAT` 清零命令计数 → 登录 → 建商户 → detail 回填 → 3 轮 50 并发热身 → 单发 1 次（刷新 L1 时间戳）→ 快照 commandstats → 第 4 轮 50 并发测量 → 再快照。修复前 jar 用 `git worktree` 从 main 检出构建（对照产物放仓库外，M2.7 教训）。

| 维度 | A：修复前（main） | B：修复后（M2.10） |
|---|---|---|
| 测量轮 Redis GET 增量 | **50**（每请求恰好 1 次） | **0** |
| 测量轮 Redis EVALSHA 增量（布隆查询） | **50**（每请求恰好 1 次） | **0** |
| RT avg / p50 / max | 19.3ms / 25.3ms / 32.8ms | 20.1ms / 19.8ms / 33.0ms |

**解读**：50 并发热点请求的 Redis 命令从 100 次（布隆 50 + GET 50）降到 **0 次**——热点流量 100% 被 L1 吸收，"本地缓存削 Redis 热点流量"实证成立。RT 无差异是诚实的观测：本机 Redis 亚毫秒往返被 HTTP 栈 ~20ms 噪声（curl 进程启动 + TCP + Tomcat）完全掩盖，真实生产中 Redis 是跨网络往返（0.5~2ms+），L1 的 RT 收益才会显现——本地实验能证明的是"命令数归零"，不是"RT 减半"。

### 4.3 实验②：Redis 宕机兜底（M2.5 雪崩成因②对策实证）

流程：建商户 → detail 预热 1 次（回填缓存）→ `docker stop localink-redis` → 分别请求热 key / 冷 key → （仅 B）等 12s 让 L1 TTL(10s) 过期再请求热 key → `docker start` 恢复后复验。

| 场景（Redis 宕机中） | A：修复前 | B：修复后 |
|---|---|---|
| 热 key（已预热） | **500** 系统错误（布隆查询失败，缓存有值也救不了） | **200 正常返回**（L1 兜底） |
| 冷 key（id=1，布隆在但从未预热） | 500 | 500（L1 miss → 布隆查询失败） |
| 12s 后再请求热 key | — | 500（L1 已过期，兜底窗口关闭） |
| Redis 恢复后 | 正常 | 正常 |

**解读**：A 组证明了 M2.5 遗留问题的存在性——布隆在前的链路里 Redis 宕机 = 整条读链路死亡；B 组 L1 提前后，**已预热的热点 key 在 TTL 窗口内继续服务**（兜底窗口 = L1 TTL = 10s，有界）。同时冷 key 依然失败说明本地缓存是"削顶"不是"高可用"——真正的高可用需要 Redis 集群 + 熔断降级，L1 只是纵深防御的一环。

实验数据已清理：实验商户 DELETE（DB 复原 10 行种子）、`lk:shop:*` 实验 key 清空、实验目录/jar 删除、worktree 移除、进程树杀净、8086 释放。

### 4.4 排障记录

- **Caffeine 容量淘汰的异步性（测试首版失败）**：`maximumSize(2)` 写入 10 条后立即断言驻留 ≤2，实测 3——容量淘汰是**异步维护**（读写操作触发缓冲，维护滞后执行）。修复：`LocalCache` 接口暴露 `cleanUp()`（Caffeine 原生 API，强制执行挂起维护），测试在断言前 cleanUp。另一个教训：首版测试假设"W-TinyLFU 按访问频率淘汰谁"（读过的 a 存活、b 被淘汰）也失败了——**W-TinyLFU 不是严格 LRU，只保证总量上界，不保证精确淘汰谁**，断言要锁定的是保证的性质而非想象的实现细节
- **commandstats 解析被贪婪匹配坑**：`sed 's/.*calls=\([0-9]*\).*/\1/'` 的 `.*` 贪婪匹配到行尾的 `failed_calls=0`，把失败计数当成了总调用数（首版实验"增量 3/0"即此假数据）。修复：锚定行首字段名 `s/^cmdstat_[a-z_]*:calls=\([0-9]*\),.*/\1/`。教训：**对结构化输出的解析要锚定字段边界，通配符+多同名字段=静默错数据**
- **Git Bash 中文 body 编码**：curl `-d` 携带中文在 Git Bash 下按 GBK 发送，服务端 UTF-8 JSON 解析直接 40001（首版脚本建商户静默失败 → SHOP_ID 空 → 后续全打 404）。修复：实验 body 全 ASCII。教训：Windows 下命令行传 JSON 避免非 ASCII，或用 UTF-8 文件 + `--data-binary @file`
- **bash 裸 `wait` 会等待所有后台任务（含 java 应用进程）**：burst 函数里 50 个 curl 后台化后裸 `wait`——脚本永久挂起，因为它在等 `java -jar` 这个永不退出的后台进程。修复：`wait "${pids[@]}"` 只等本轮 curl；后进一步改 `curl --parallel` 单进程并发
- **多进程并发 append 同一文件丢行**：50 个后台 curl 各自 `>>` 同一个 RT 文件，Windows 下只落了 19/50 行（并发 append 非原子，互相覆盖）。修复：`curl --parallel` 单进程输出，天然原子

## 5. 学习清单

**核心知识点**
1. 三级纵深的完整成本账（本项目落地版）：L1 Caffeine（进程内，0 网络往返，纳秒级，TTL 10s）→ 布隆（1 次 Redis 往返，拦"从未存在"）→ Redis（1 次 Redis 往返，分布式一致 + 逻辑过期防击穿）→ DB（事实源）。实测热点请求从 2 次 Redis 命令降到 0 次——每层解决上一层的瓶颈：DB 怕压（旁路缓存）、Redis 怕热点与宕机（L1）、单层缓存怕穿透/击穿/雪崩（M2.4~M2.9）
2. 读链路顺序的再演进（推翻自己一版决策）：M2.9 把布隆放最前的理由是"缓存查询=1 次 Redis 往返，先布隆更划算"；L1 的出现打破了这个前提——进程内查询零网络成本，理应最前。**附加收益是故障可达性**：布隆查询本身依赖 Redis，布隆在前的世界里 Redis 宕机 = L1 永远走不到（实验② A 组实证 500）。层级顺序的设计要看"上游依赖故障时各层是否还可达"
3. Caffeine 核心机制：`expireAfterWrite`（写后计时过期，与访问无关——本场景选它因为"新鲜值回填"定义了生命周期起点）；`maximumSize` + W-TinyLFU 淘汰（新 entry 进 admission window 按 LRU，按频率准入主缓存 SLRU 试用/保护段，Count-Min Sketch 记频率带老化——频率+新近性混合，防扫描污染，但**不是严格 LRU**）；容量淘汰是**异步维护**，需要确定性状态时 `cleanUp()`（本迭代测试实测 3>2 踩坑）
4. L1 只存新鲜值的边界纪律：逻辑过期旧值不进 L1（把毫秒级重建窗口的脏值拉长到 10s 得不偿失，且重建完成 L1 感知不到）；空值/NOT_FOUND 不进 L1（空值是无限集合，同 M2.7 正缓存无 TTL/空值短 TTL 的不对称逻辑）；null 值 put 在框架层静默忽略
5. 进程内缓存存对象引用的双刃剑：读写零序列化成本（对比 Redis 每次编解码），但所有命中方共享同一实例——调用方若修改 VO 会污染缓存。本项目调用链只读返回（Controller 序列化即丢弃）安全；若未来要防，进化方向是存不可变对象或防御性拷贝
6. 多级缓存一致性：写路径"先更库 → 删 Redis → 失效 L1"，失效永远优先于更新（更新有读旧值回填的竞态）。单实例即时一致；多实例他实例 L1 残留最长 = TTL（10s，有界）——根治靠 Kafka 广播失效（M3.9），本迭代"记录而非解决"
7. 两个伏笔的评估闭环（M2.9 预告）：①泛型 entry 序列化下沉——L1 存对象引用不经序列化，**没有产生第二个 TypeReference 场景**，维持 M2.7 决策继续延迟（等 M3 秒杀券详情这类真正的第二个 Redis 泛型场景）；②TTL 抖动下沉——L1 过期只回落到受保护的 Redis 层不砸 DB，无需抖动，维持 M2.5 决策。**"评估后不做"也是决策**，两个"等"都写明了等的是什么
8. Redis 宕机兜底的有界性：只救"TTL 窗口内已预热的热 key"（实验② 10s），冷 key 立即失败——本地缓存是纵深防御的削顶一环，不是高可用方案；真正 HA = Redis 集群/哨兵 + 熔断降级 + L1 兜底的组合

**面试必题**
1. "已经有 Redis 了为什么还要本地缓存？"——两个理由报实测：①削 Redis 热点流量（50 并发测量轮 Redis 命令 100 → 0）；②Redis 故障时的有限兜底（宕机后热 key 200 vs 修复前 500）。代价同样要说：一致性窗口、JVM 内存、多实例失效复杂度——没有免费的层
2. "本地缓存和 Redis/DB 的一致性怎么保证？"——写路径先更库再逐层失效（删 Redis + invalidate L1）；失效优先于更新（更新有竞态）；单实例即时，多实例广播失效（M3.9 Kafka）+ TTL 窗口兜底。追问"为什么不等 TTL 自然过期"——写少读多场景主动失效把脏窗口从 TTL 级压到瞬时
3. "L1 的 TTL 和容量怎么定？"——TTL = 一致性窗口 vs 削流时长的权衡（10s：商户详情可接受，热 key 一个窗口内 0 Redis 流量）；容量 = 热点集合规模（1000，全表 10 万级也只缓存热点子集）+ W-TinyLFU 自动淘汰冷 key。两个参数 yml 可配，没有万能值只有业务权衡
4. "Caffeine 的 W-TinyLFU 是什么？和 LRU 什么区别？"——窗口 LRU 收新、按频率准入 SLRU 主缓存、CM-Sketch 频率草图 + 半衰期老化；比 LRU 抗扫描污染（偶发批量扫描不会冲掉真热点），比 LFU 抗历史包袱（老化让旧热点退位）。实测教训：它只保证总量上界不保证淘汰谁——测试断言锁保证的性质
5. "Redis 挂了你的多级缓存能撑多久？"——已预热 key 撑到 L1 TTL 到期（10s），冷 key 立即失败（布隆查询依赖 Redis）。本地缓存不是高可用，是纵深防御的一环——这个问题考察的是知不知道每层的能力边界
6. "为什么逻辑过期的旧值不写进本地缓存？"——旧值进 L1 会把"异步重建毫秒级完成"的脏值窗口人为拉长到 L1 TTL，且 Redis 重建完成后 L1 无感知；不进则过期窗口内请求照旧走 Redis（保持 M2.7 行为）。细节：回填点只有两处——Redis fresh entry 分支和查库成功后
7. "读链路 L1 放布隆前面，穿透防护会不会变弱？"——不会：随机 id 从不在 L1（L1 只存验证过的新鲜正缓存），miss 后照旧被布隆拦（实验数据未变）；反而白赚两个收益——热 key 连布隆往返都省了（50→0 命令）+ Redis 宕机时 L1 可达。层级顺序要看上游依赖故障时的可达性

## 6. 下一步

M3.1 秒杀 V1：纯 DB 下单（含一人一单 DB 实现）——M2 缓存体系收官，进入秒杀核心链路。
