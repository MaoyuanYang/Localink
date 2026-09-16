# 迭代任务卡：M3.4 分布式锁框架②——@ServiceLock 注解 + SpEL + AOP + 超时策略

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.4（B2 批次，与 M3.3 同批实施） |
| 分支 | `feature/m3-3-m3-4-lock-starter` |
| 状态 | 已完成（2026-09-16） |

---

## 1. 目标

让业务方法一行注解获得分布式锁能力：`@ServiceLock(name = "seckill:order", key = "#voucherId")`，切面负责 SpEL 求值、拼 key、拿锁、执行、释放、超时抛错。命令式 API（M3.3）保留给需要精细控制的场景（如锁内再决策）。

## 2. 设计取舍

- **两段式 key：`name()`（业务语义段）+ `key()`（SpEL 变量段）**，最终 key = `localink.lock.key-prefix`（默认 `lk:lock:`）+ name + ":" + 求值结果。备选：接 KeyBuilder/KeyManage 治理——KeyBuilder 在 cache-starter，lock 依赖它违反"starter 不互相依赖"铁律；治理对接（KeyBuilder 下沉 common 或 SpEL 引用 bean）**留作 M3.5 业务接入时的设计题**，本迭代以独立 keyPrefix 保持环境隔离能力
- **切面 `@Order(0)`，锁在事务外层**：事务拦截器默认 `Ordered.LOWEST_PRECEDENCE`（最内层），切面数值更小→更外层→执行顺序"拿锁→开事务→提交→释放锁"。若锁在事务内：unlock 先于 commit，下一个持锁者可能读到未提交前的旧数据（经典坑）。M3.5 秒杀接入时此顺序是正确性前提
- **SpEL 求值用 `MethodBasedEvaluationContext` + `DefaultParameterNameDiscoverer`**：父 pom 已开 `-parameters` 编译，`#voucherId` 直接按参数名取值；解析出的 Expression 按"表达式字符串"维度缓存（ConcurrentHashMap），避免每请求重复 parse
- **key 求值为空即抛 PARAM_ERROR**：拼出 `lk:lock:seckill:order:` 这种尾空 key 会把所有请求锁到同一把锁上（静默串行化整个接口），必须快速失败
- **超时策略只做 FAIL（抛 LOCK_TIMEOUT）**：备选 hmdp 式 customStrategy 反射回调（本阶段无业务需求，YAGNI，记为演进备选）
- **受检异常载体**：`pjp.proceed()` 可抛受检异常而 `Supplier` 不允许——用私有 RuntimeException 子类包装穿越锁模板，在切面入口 catch 拆包重抛；RuntimeException/Error 原样直传（LocalinkException 不受影响）
- **注解默认值**：waitTime=3s、leaseTime=-1（看门狗）、type=REENTRANT——秒杀场景等待宜短、业务时长不可预估宜看门狗

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `annotation/ServiceLock` | name/key/type/waitTime/leaseTime/timeUnit 六属性 |
| `aspect/ServiceLockAspect` | @Around + SpEL 缓存求值 + 两段式拼 key + 超时 + 受检异常拆包 |
| `LockAutoConfiguration` +serviceLockAspect Bean | @ConditionalOnBean(DistributedLock) 链式装配 |

## 4. 验证记录（2026-09-16 本机实测）

集成测试 3 例：

| 用例 | 断言要点 | 结果 |
|---|---|---|
| spelKeyResolvesToDocumentedRedisKey | **用"他线程占位目标 key→调用必超时"证明最终 key 形态恰为 `lk:lock:test-order:123`**；释放后调用成功返回 | ✅ |
| concurrentCallsOnSameKeyAreSerialized | 8 线程同 key 并发 → totalEntered=8 且 maxConcurrent=1 | ✅ |
| waitTimeExhaustionThrowsLockTimeout | 持有者占锁期间第二调用 200ms 等待耗尽 → 40005 | ✅ |

全量回归 145/145（见 M3.3 任务卡）。

**排障记录**：
1. **首版 key 形态测试失败——同线程可重入让"占位"失效**：原设计主线程先 `blocker.tryLock()` 占住目标 key，再调 `process("123")` 期望超时；但切面在**调用线程**上获取锁，同线程对同一把可重入锁直接二次获取成功，无异常抛出。这恰好实证了 M3.3 讲的可重入语义（field=线程标识，同线程放行）。修复：占位锁改由**独立线程**持有。教训：可重入锁的测试必须在跨线程视角下设计

## 5. 学习清单

**核心知识点**
1. **AOP 环绕通知与切面顺序**：@Around 里 `pjp.proceed()` 前后即"拿锁/释放"的天然位置；@Order 数值越小越外层。多个切面/拦截器叠加时执行顺序是隐式契约，锁与事务的相对顺序是正确性问题不是风格问题
2. **SpEL 求值上下文**：表达式不是字符串替换，是在带"方法参数名→实参"绑定的上下文里求值；`-parameters` 编译参数让参数名在运行期可读（否则只能用 #a0/#p0）
3. **锁为什么必须在事务外**：unlock 先于 commit 的窗口里，下一持锁者读库拿到的是旧快照——一人一单校验会被击穿。顺序：acquire → begin tx → business → commit → release
4. **受检异常 × 函数式接口**：Supplier/Runnable 的签名不含受检异常，lambda 内抛受检异常编译不过；标准解法是载体 RuntimeException 包装+边界处拆包（本框架 CheckedProceedThrowable）
5. **性能细节**：SpEL parse 昂贵、求值便宜——按表达式缓存 Expression；锁 key 求值失败要 fail-fast 而不是拼出静默错误的 key

**面试必问题**
1. "@ServiceLock 是怎么生效的？"——@Around 环绕 + Spring AOP 动态代理；注解本身只是元数据，切面读它驱动命令式锁
2. "锁和 @Transactional 谁在外面？为什么？"——锁在外；否则释放锁时事务未提交，并发读旧数据
3. "SpEL 解析参数名为什么能work？"—编译期 -parameters 保留参数名 + ParameterNameDiscoverer 读取
4. "注解方式比命令式好在哪？什么时候用命令式？"——声明式零模板代码、锁语义集中在注解；命令式适合锁内分支决策、嵌套锁、非方法边界的热点
5. "超时了怎么办？会重试吗？"——不重试，抛 LOCK_TIMEOUT 交调用方决策（秒杀场景直接失败回"繁忙"）；重试策略若引入需防重试风暴

## 6. 下一步

**M3.5 一人一单升级：唯一索引兜底 + 分布式锁对比演示**——首次把 B2 的锁用在业务上：seckill 加 `@ServiceLock(name="seckill:order", key="#voucherId")`，与 M3.2 实验二复现的"一人 10 单"对照实验；同时补唯一索引兜底（DB 最后防线）；顺带决断锁 key 与 KeyManage 治理的对接方式、替换 SHOP_REBUILD_LOCK 自研锁。
