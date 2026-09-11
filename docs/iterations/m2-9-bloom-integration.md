# 迭代任务卡：M2.9 布隆过滤器接入：启动初始化灌数据 + 查询链路前置拦截

| 字段 | 内容 |
|---|---|
| 迭代编号 | M2.9 |
| 分支 | `feature/m2-9-bloom-integration`（基于 `feature/m2-8-bloom-framework` 栈式创建，依赖 PR #40） |
| 状态 | 已实现（PR #41 待合入，2026-09-03；合入并学习确认后勾选 roadmap） |

---

## 1. 目标

把 M2.8 的布隆过滤器框架接入商户详情读链路，根治 M2.4 遗留的穿透缺陷：空值缓存只能拦截"打过的"不存在 id，**攻击者每次换随机 id 都是一次全新 miss**，空值 key 被无限制造（M2.4 任务卡刻意保留的演进证据点）。本迭代在 `detail()` 最前面加布隆前置拦截——"从未存在"的 id 在进缓存/DB 之前就被拒绝；空值缓存退守第二层，专门兜底"曾经存在但已删除"的 id（布隆不可删的固有误判）。两层防线的完整组合：**布隆拦从未存在、空值缓存拦曾经存在、互斥锁/逻辑过期防击穿**。

涉及表：`lk_shop`（无变更，启动全量扫描 id）；涉及接口：`GET /api/shop/{id}`（对外行为：从未存在的 id 依旧 NOT_FOUND，但 0 次 Redis 写、0 次 DB 查）；涉及 Redis Key：新增 `lk:shop:bloom:id`（Redisson RBloomFilter 位图 + `{lk:shop:bloom:id}:config` 参数哈希，无 TTL 跨重启保留）。

## 2. 设计取舍

- **前置拦截放 `detail()` 第一行，顺序为"布隆 → 缓存 → DB"**：布隆说"一定不存在"的 id 直接抛 `NOT_FOUND("商户不存在")`，不进 Redis 不进 DB。备选否决：布隆判断放布隆之后查缓存（hmdp 参考项目把布隆放在缓存 miss 之后）——缓存 miss 的随机 id 反正要被拦，先查缓存白白多一次 Redis 往返且让正缓存命中路径多背一层逻辑；先布隆的代价是**每个请求多一次布隆查询（也是一次 Redis 往返）**，但它同时保护了缓存和 DB 两个后端。误判的代价可控：布隆误放行的 id 走原有 miss 链路（互斥重建 → 查库 → 空值标记），等效于没有布隆的行为，不放大故障
- **启动灌数据用 `ApplicationRunner` 全量 selectList**：容器就绪后、对外服务前把全表 id 灌入。备选否决：hmdp 的 `@PostConstruct`（bean 初始化期查库，上下文未刷新完就做 IO，启动期依赖更脆）；`ApplicationReadyEvent` 监听（与 Runner 等效，无额外收益）。已知取舍：Runner 执行完之前 Web 已可接请求，极小窗口内"表里有但布隆还没灌到"的 id 会被误拦——商户详情 miss 即重建本身能兜住，选择记录而非解决（最小迭代）。幂等性：RBloomFilter.add 对已存在元素无副作用，重启重灌=对账，位图持久在 Redis（AOF），日常重启其实不灌也对，重灌只是把"中途 create 的数据"兜进来的保险
- **create() 落库后同步 add 进布隆**：新商户必须立即可查，否则"创建成功→详情 404"的业务矛盾。delete() **不动布隆**（布隆固有不可删）：已删商户仍会通过布隆（误判方向的"漏放"），落到空值缓存路径兜底——这正是两层防线各司其职的闭环，也是 M2.4 学习清单预告的"校验+布隆+空值"组合拳。update() 无需动作（id 已在布隆）
- **存量测试改造方向：空值缓存场景从"从未存在"迁移到"曾经存在"**：原 `detailOfMissingShop*` 用 nanoTime 造 id，布隆拦截后根本走不到空值写入——这不是测试失效而是**语义迁移**：空值缓存的防区从"所有不存在 id"收窄为"布隆放行但 DB 没有的 id"（=已删除商户）。改造为 create 进布隆 → 直删 DB 行（模拟删除后布隆残留）→ detail 断言布隆放行 + 空值标记写入
- **过滤器别名与 key 模板的双登记**：别名 `shop` 集中在 `BloomFilterAlias` 常量类（对照 yml 的 filters map key），key 模板 `shop:bloom:id` 在 KeyManage 登记并新增契约测试锁 `lk:shop:bloom:id`——yml 与登记处两处镜像，靠契约测试防漂移（与 M2.2 Key 治理一脉相承）
- **yml 参数：expected-insertions=100000 / false-probability=0.01**：商户规模按 10 万预留（当前 10 条种子+测试造数，余量 4 个数量级），0.12MB 内存成本可忽略；不追求贴身参数，布隆扩容需删重建，宁可冗余

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `application.yml` | + `localink.cache.bloom.filters.shop`（key-template: shop:bloom:id, expected-insertions: 100000, false-probability: 0.01） |
| `constant/KeyManage.java` | + SHOP_BLOOM 登记（desc 写明契约：无 TTL、启动全量灌+create 同步 add、不可删→空值兜底） |
| `constant/BloomFilterAlias.java`（新增） | 过滤器别名常量 `SHOP = "shop"`，与 yml map key 对应，杜绝散落硬编码 |
| `framework/cache/ShopBloomFilterInitializer.java`（新增） | ApplicationRunner：`shopMapper.selectList(null)` 全量灌入 + log 灌入条数 |
| `service/impl/ShopServiceImpl.java` | `detail()` 首行布隆前置拦截（不存在直接 NOT_FOUND）；`create()` 落库后 `bloomFilterRegistry.add`；注入 BloomFilterRegistry |
| `test/.../KeyManageTest.java` | + `shopBloomKeyMirrorsYmlTemplate` 契约：`lk:shop:bloom:id` + 无默认 TTL |
| `test/.../ShopCacheIntegrationTest.java` | **新增 3**：`neverSeenIdRejectedByBloomBeforeCacheAndDb`（随机 id：NOT_FOUND + selectById never + 0 缓存写入）、`createdShopIsAddedToBloomFilter`（create 后 contains 真）、`initializerBackfillsAllExistingShopsIntoBloom`（mapper 直插行→手动 run Initializer→contains 真，验证存量回灌）；**改造 2**：两个空值场景迁移为"已删除商户"（`deletedShopStillPassesBloomAndWritesEmptyMarkerWithShortTtl` 布隆放行+空值标记+短 TTL 窗间、`emptyMarkerAbsorbsRepeatedDeletedShopMissesWithoutDbHit` 二次请求 selectById 仍恰 1 次）；**微调 1**：`deleteRemovesShopAndCache` 保留 createdIds 清理空值标记 + 断言空串兜底 |

读链路时序：`GET /api/shop/{id}` → **布隆 `contains(shop, id)`**：false → NOT_FOUND（0 Redis 写、0 DB 查，防线①）→ true（可能误判）→ `GET lk:shop:info:{id}` → null → 互斥锁同步重建（查库 miss → 写空值标记 2min+抖动 → NOT_FOUND，防线②，收窄为"已删除商户"）→ 空串 → NOT_FOUND → entry 未过期 → 直返 → 过期 → 异步重建+旧值兜底。

写链路时序：`POST /api/shop` 落库 + 布隆 add；`PUT /api/shop` 先更库再删缓存（不动布隆）；`DELETE /api/shop/{id}` 先删库再删缓存（不动布隆，残留位由空值缓存兜底）。

## 4. 验证记录（2026-09-03 本机实测）

### 4.1 常规验证

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy |
| 全量构建 | `./mvnw clean package` | BUILD SUCCESS，12 模块 |
| 全量测试 | 同上 | **115/115 通过**（cache-starter 41 含 M2.8；server 74 = M2.8 时 70 + KeyManage 契约 1 + ShopCache 10→13） |
| 定向明细 | ShopCacheIntegrationTest | **13/13**：新增 3（布隆拦从未存在 0 查库 0 写 / create 入布隆 / 启动回灌）+ 改造 2（已删商户空值标记 / 空值吸收）+ 存量 8 全过（造数均经 service.create 自动进布隆） |
| 定向明细 | KeyManageTest | 6/6：+`lk:shop:bloom:id` 契约 |
| 存量回归 | ShopAuth / ShopCrud / Seckill / Voucher / User / Sms 等 | 全过——server 所有 @SpringBootTest 启动即跑灌数 Runner（lk_shop ~10 行，启动耗时 +几百 ms 可忽略） |
| 启动冒烟 | smoke.ps1 / 手工 `java -jar` | PING-OK；启动日志 `商户布隆过滤器灌入完成, count=10` |

### 4.2 随机 id 扫射实验（铁律②实证收尾：M2.4 缺陷根治对照）

场景：清空布隆 key → 启动应用（自动灌入 10 条种子）→ 记录基线 DBSIZE=4 → `GET /api/shop/1`（正常）→ 随机大 id 连续 20 次攻击 → 复查。

| 维度 | 结果 |
|---|---|
| 种子商户 id=1 | `code:0` 正常返回详情 |
| 20 次随机 id 攻击 | **20/20 `code:40004`（NOT_FOUND）** |
| 攻击贡献的新 key | **0 个**（`SCAN lk:shop:info:9999999*` 空） |
| DBSIZE 变化 | 4 → 7 = 基线 4 + 布隆位图/参数 2 + id=1 缓存 1，**攻击零贡献** |
| 对照 M2.4 记录 | 同场景下空值缓存方案会写入 **20 个空值 key**（每次换 id 一个新 key，随机 id 扫射可打爆 Redis 内存）——本迭代后归零 |

实验数据已清理：`lk:shop:info:1`、布隆 2 个 key 已删（DBSIZE 复原 4），应用进程已杀净。

### 4.3 排障记录

- **server 侧第二个 Spring 上下文集体启动失败（45 个测试 Errors）**：M2.9 首轮全量测试，第一个上下文正常建布隆，后续所有上下文在 `bloomFilterRegistry` 构造期抛"已存在且参数不一致"。探针实证：Redisson `tryInit` 返回 false 只表示"Redis 已存在该过滤器"，**与参数是否一致无关**（同参也返回 false），参数校验须用 `getExpectedInsertions()/getFalseProbability()` 回读比对（实测精确回读）。修复在 M2.8 框架层（PR #40 已补 fix 提交）：tryInit 失败 → 回读校验，同参复用、异参才快速失败，并补 2 个二次构造回归测试。教训：**对第三方 API 返回值语义的假设要在第一时间用实验钉死**——首版把 tryInit==false 直接当参数冲突，正确语义下连"多上下文共享同参过滤器"（本迭代多测试上下文、生产多实例部署的核心价值）都会被误杀

## 5. 学习清单

**核心知识点**
1. 布隆防穿透的完整分层（本项目落地版）：**布隆（拦"从未存在"，0 后端压力）→ 空值缓存（拦"曾经存在"=布隆漏放的已删数据，1 次 DB 后吸收）→ 互斥锁/逻辑过期（防"存在但缓存失效瞬间"的击穿）**。三层各管一段，组合拳而非单点——面试讲清"为什么空值缓存不能删"就靠这张分层图
2. 布隆不可删的工程善后：删除的 id 残留在位图里成为"漏放"来源（布隆说存在→查库没有）——漏放无害，落到空值缓存即被吸收（2min 短 TTL），两层防线恰好互补：布隆的删除缺陷被空值缓存的写入吸收，空值缓存的随机 id 缺陷被布隆的拦截消灭
3. 启动灌数据的三个姿势与选择：`@PostConstruct`（bean 初始化期，上下文未就绪做 IO）/ `ApplicationRunner`（上下文就绪后、Runner 完成前 Web 已监听，极小窗口）/ `ApplicationReadyEvent`（完全就绪后）。本项目选 Runner：标准、可直接在测试里手动调（回灌测试就是这么验证的）；窗口期由"miss 即重建"兜底
4. 布隆数据的生命周期维护：启动全量灌（对账）+ 写链路同步 add（增量）。位图持久在 Redis，重启不丢——重灌是保险不是必需；调参/清洗需删 `key` + `{key}:config` 两个结构重建
5. 误判率的方向性：布隆 false = 一定不存在（拦截权威），true = 可能存在（漏放，走兜底链路）。所以布隆只能做"拒绝"决策不能做"放行"决策——前置拦截的语义是"挡掉肯定不存在的"，不是"确认存在的"
6. 读链路的完整成本账：布隆拦截请求 = 1 次 Redis 布隆查询（EVALSHA + 位图读）；布隆放行 = 布隆查询 + 原链路。防穿透的代价是每个请求 +1 次 Redis 往返——换来的是随机 id 攻击 0 缓存写、0 DB 查（实验实证 20/20 拦截、0 key 残留）
7. 测试语义随防线演进而迁移：空值缓存的防区从"所有不存在 id"收窄为"布隆漏放的 id"——旧测试用 nanoTime 造"从未存在"id 会撞上新防线，正确做法不是放宽断言而是**迁移场景到新防区的语义**（已删除商户），让每层防线都有专属测试守着
8. yml 配置与代码登记处的镜像治理：过滤器 key-template 在 yml（框架按配置装配）+ KeyManage（业务唯一登记处）两处出现，靠 KeyManageTest 契约锁字符串防漂移——配置驱动的框架接入 Key 治理的实践范式

**面试必题**
1. "缓存穿透三连问：怎么防？空值缓存够吗？"——参数校验（拦非法）→ 布隆（拦从未存在）→ 空值缓存（拦漏放/已删）。空值缓存单独不够：随机 id 每个都是新 key，防住的是"重复轰炸"不是"分布式扫射"（报得出自己 20 次攻击 0 key 残留的实测）
2. "布隆误判了怎么办？会误拦吗？"——不会误拦（false 无漏报）；误判只会"漏放"（false positive），漏放的 id 走正常缓存+DB 链路并写空值标记，等效于没有布隆——防御性结构要选"错误方向安全"的
3. "布隆不能删除，删除的商户怎么办？"——残留位漏放 → 空值缓存吸收（短 TTL 空串标记），后续请求被空值标记直接拦掉；根治要计数布隆/布谷鸟过滤器，代价与复杂度上升，工程上常用"接受漏放+下游兜底"
4. "布隆过滤器数据怎么来？重启会丢吗？"——启动全量灌 + 写链路同步 add；Redisson RBloomFilter 位图在 Redis（AOF 持久化），重启不丢，重灌是对账；Guava 本地布隆重启即丢、多实例不共享——分布式场景选 Redisson 的理由
5. "新增数据写布隆失败了怎么办？"——本项目 create 落库后同步 add（同事务性弱：add 失败抛异常但库已插——漏放的逆问题"漏拦"，该 id 永远进不了缓存前的判断...实际方向是布隆缺位→误拦新商户；兜底：重启重灌或对账任务。主动讲出这个边界和兜底是加分项
6. "你这防穿透方案的代价是什么？"——每请求 +1 次 Redis 往返（布隆查询）+ 启动全表扫描（当前 10 行忽略不计，百万级要评估改增量对账）+ 布隆 key 常驻内存（10 万条 1% 误判 ≈0.12MB）。没有免费的防御，报得出代价才算真懂方案

## 6. 下一步

M2.10 Caffeine 本地缓存：双层读链路——本地缓存（Caffeine，进程内纳秒级）+ Redis（分布式一致）+ DB 三级纵深，进一步削 Redis 热点流量；同时把 M2.7 预告的"泛型 entry 序列化下沉框架层"拿到台面评估。
