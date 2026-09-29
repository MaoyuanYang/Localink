# 主题任务卡：M8 全量审计与修复（project-verify 五维审计 → P1/P2/文档漂移全量修复）

| 主题 | M8（已完成） |
|---|---|
| 分支 | `feature/m8-audit-fixes`（一主题一分支一 PR） |
| 状态 | 已完成 —— 2026-09-29（审计报告 `docs/VERIFICATION.md`：23 主题全 Verified、287/287 干净全绿×2、性能头条复现 12683 QPS；本卡为审计发现的修复交付） |

## 1. 目标

对审计报告的全部发现做一次性修复：7 项 P1、19 项 P2、可低风险加固的 P3、以及 D-1~D-19 文档漂移回溯。原则：**行为缺陷修复、已声明的设计取舍保留并在本卡逐条说明理由**；每个 P1/P2 修复配套可执行验证（新增 9 项测试 + 全量回归）。

## 2. 修复清单（发现编号 ↔ 实现）

### P1（7/7 全修）

| 编号 | 修复 | 位置 |
|---|---|---|
| A-1 | 撞唯一索引时同事务 `restoreStock` 回补（幻影扣减归零） | `VoucherOrderServiceImpl.createSeckillOrder` |
| A-2 | `closeOrderIfExpired` 加 `@Transactional`（条件关单+DB 回补同事务）；Redis 回滚/回流发券挪 `TxCallbacks.afterCommit` | 同上 |
| A-3 | 对账新增 `closeOverdueCreatedOrders` 补裁：status=1 且超「关单延迟+宽限期」的挂单走补关单（并计入 `localink.order.close` 指标） | `ReconciliationJob` |
| A-4 | 商户更新/删除后**延迟双删**（1s，CompletableFuture 延时调度器，不占重建池），杀在途重建旧值回填窗口 | `ShopServiceImpl.delayedDoubleDelete` |
| A-5 | 预通知 SETNX 走门面原子 `setIfAbsent(key,"1",TTL)`（KeyManage 登记 1 天），`StringRedisTemplate` 依赖整体移除 | `SeckillNoticeConsumer` |
| A-6 | 三道防线：ShopDTO 经纬度 `@DecimalMin/@DecimalMax` → 40001；`addToGeo` 范围防线；`ShopGeoInitializer` 逐店 try-catch（脏行跳过不再拖垮启动）；create/update/delete 加事务（GEO 失败回滚 DB，脏行无从落库） | `ShopDTO` / `ShopServiceImpl` / `ShopGeoInitializer` |
| A-7 | `@AdminOnly` 注解 + `AdminGuardInterceptor`（order 2）+ `localink.security.admin-guard.enabled/admin-phones` 配置；默认关闭=演示单机全放行，开启后非白名单手机号 40003。挂到 Shop/ShopType/Voucher/SeckillVoucher 四组写端点（12 个），买家动作（token/seckill/claim）不受影响 | `framework/auth/*` + 四控制器 |

### P2（19/19 全修）

| 编号 | 修复 |
|---|---|
| B-1 | 秒杀进行中禁止修改库存（stock 值变化且 now∈[begin,end) → 40001）；`selectOne` 判空；warm/evict 挪 afterCommit（事务回滚不再残留 Redis） |
| B-2 | 并入 A-1（同一实现位） |
| B-3 | `/api/notice`、`/api/seckill-voucher/{id}/subscribe` 游客访问判空 → 40002（原 NPE→500） |
| B-4 | `DELETE /api/user/logout`：删除 Redis 会话，token 立即失效 |
| B-5 | create 改**布隆先入、DB 后写**（IdWorker 预生成 id）：Redis 抖动不再产生"已建商户被布隆永久误拦"半完成态 |
| B-6 | Feed 拉路径 score 时间位取"所在秒末毫秒"（ceiling）：推路径已服务的游标拉路径不再放行同帖；同秒并列由归并插入序（DB id 倒序）保持确定 |
| B-7 | 新增 `CommunityErrorHandlerConfig`：post-audit/post-search 挂指数退避+Recoverer——审核耗尽**强制驳回**（fail-closed，`forceReject`），ES 耗尽 error 告警（rebuild 可重算） |
| B-8 | 点赞榜 ZSet 空 → DB liked 重算回退；关注集空 → DB 回查**写穿回缓存**（feed/commonFollows 双点，commonFollows 因 SINTER 无法区分空集与丢失、空结果一律 DB 复核） |
| B-9 | `SearchRebuildRunner`：`localink.search.rebuild-on-start` 开关触发启动全量重建（不开 HTTP 端点，维持 deploy §7 防误触决策） |
| B-10 | 驳回对齐删帖链路清理派生视图：ZREM 点赞榜/热榜 + DEL UV key |
| B-11 | 失败表重试废弃"删旧走新"：成功才删行；失败保留原行（create_time 不重置）+ retry_attempts 递增 + 删同 (voucher,user) 更新重复行——行龄 24h 告警可达；`rollbackSeckillQualification` 改返 boolean（true=已收敛） |
| B-12 | 对账逐笔 try-catch 隔离：单条毒流水只跳过自身，不再中止整轮 |
| B-13 | workId 分配失败仍回退 0，但注释改为与行为一致（log.error 继续启动；单机演示口径显式声明） |
| B-14 | `TokenRefreshInterceptor` 会话读取/解析/续期全部捕获：Redis 故障→按匿名处理（受保护端点 40002 而非 500；公开 GET 走 L1 兜底） |
| B-15 | 保留（已文档化取舍，见 §3） |
| B-16 | 库存预热/令牌发放改原子 `set(key,value,ttl)`；Hash 写入语义见取舍节 |
| B-17 | DB 三处连接参数环境变量化（`LOCALINK_DB_URL/USER/PASSWORD`、`LOCALINK_DB1_URL`），默认值保本机演示直跑 |
| B-18 | 验证码不再落日志（`code=******`，调试从 Redis 取） |
| B-19 | 分页/参数钳制：shop/page、post/page（≤50）、search size（1~50）+ 关键词 1~64 字符；GEO 范围（A-6）；券金额 `payValue < actualValue` 校验（普通+秒杀） |

### P3 加固（已修）

线程池优雅收尾（waitForTasksToComplete+10s）、Caffeine `recordStats()`、Redisson `setTimeout(3000)/setPingConnectionInterval(30000)`、布隆初始化只查 id 列、DFA **分域扫描**（title/content 不再拼接跨字段误杀）、评论删除条件递减防负数 + 楼中楼 `LIMIT 500`、上传 delete `normalize()+startsWith` 防穿越、`UserBriefVO.userId` 字符串化（D-14，防 19 位精度丢失）、`MetricsPort`（common 接口/starter 可选注入/server Micrometer 实现）+ **actuator 引入**（D-15：`localink.rollback.failure`/`localink.ratelimit.rejected`/`localink.reconcile.compensated`/`localink.order.close` 暴露到 `/actuator/metrics`，health/info/metrics 端点开放）。

### P3 保留项（已声明取舍，不改行为）

事务内非 DB IO（Top买家/延迟投递——已有对账/幂等兜底，注释声明）、XFF 信任与限流 fail-open（网关层职责）、令牌先烧后校验（严格一次性语义）、秒杀热路径 3 次 DB 查询（M7 已归因为后续演进）、分片 INLINE 负值（雪花恒正的隐式契约已在 sharding.md 声明）、`PingController` 裸 String（冒烟端点）、幂等回放泛型（当前消费场景返回 void）、nearby 无缓存（GEO 场景读频低）、ES liked/nickName 快照失真（与既有声明同口径）、`spring.datasource.hikari.*` 双轨暗坑（历史归因记录，primary-pool 已接管）。

### 文档回溯（D-1~D-19）

roadmap：M3 锁口径改现行防线表述 + 新增 M8 节；m1-3 补分层频控口径（服务层 20001 / HTTP 切面 40008 并存——**审计 D-13 原判"失实"修正为"分层口径"**）；m2-3 补逻辑过期演进注记；m2-9 修正自相矛盾；m5-reconcile 标注删旧走新废弃；m5-delay-close 补兜底链路演进；m6-feed 游标方向修正 + ceiling 口径；deploy §3 SQL 归属修正（localink.sql=12 表含逻辑表，物理表在 sharding.sql）+ FAQ 补串行测试纪律；interview-qa 53 张；architecture V1 口径统一"300 并发 500 请求"；PRD actuator 标注兑现；AGENTS Knife4j 标注未引入 + 测试串行纪律；KeyManage javadoc 登记 scan/entries 治理例外；VERIFICATION.md 附修复状态（随本 PR）。

## 3. 设计取舍

- **A-1 用"撞键补偿"而非"异常穿透"**：穿透会让 Recoverer 走回滚（回流场景用户已持有效单，回滚会误退真单资格）；同事务补偿净效果为零，语义精确。MySQL 语句级回滚不污染事务，补偿 UPDATE 安全。
- **A-7 默认关闭**：开启默认会破坏全部既有测试与压测脚本口径（普通用户建券）；以配置开关交付机制、以文档要求生产开启——演示作品与真实部署的边界显式化。
- **B-6 ceiling 而非持久化 score**：同秒内更晚的未推大 V 帖可能被跳过（游标落秒中段时），换取跨页重复归零；推路径已服务的场景全部正确。不引入新存储。
- **B-8 读路径回退而非定时重算任务**：DB 回查+写穿是最小闭环；定时重算任务（对齐热榜）属后续演进。
- **B-11 保留原行**：失败行的 create_time 是"首次失败时间"这一事实载体，行龄告警语义依赖它；"删旧走新"原意是收敛防重复，改由"成功才删+去重"承担。
- **B-16 的 Hash（会话）写仍两步**：改为"先 expire 再 putAll"语义颠倒了失败模式（产生短命空 hash→按未登录处理，安全）；Spring Data 无单命令 HSET+EXPIRE，pipeline 改造收益低。
- **B-13 注释对齐而非改行为**：多实例 Redis 故障期同启撞号是极端场景，"拒绝启动"对演示单机是过度设计；注释与行为一致 + 本卡声明即消除"注释与实现相反"的审计矛盾。

## 4. 验证记录（2026-09-29 本机）

- **新增测试 9/9 绿**：`AuditFixOrderConsistencyIntegrationTest`（A-1 幻影扣减归零：重复建单后库存不少 1、无新订单；A-3 补关单：超龄挂单 status→3、DB 库存回补）+ `AuditFixWebHardeningIntegrationTest`（B-3 双端点 40002、B-4 登出即失效、B-19 越界纬度/负价值券 40001、DFA 分域放行、A-7 闸门三态）。
- **全量回归**：`mvnw clean test` 串行独占环境 **296/296 全绿**（287 + 新增 9；server 187→196）。
- **审计期间运行时证据**（修复前采集）：notice:sent 148 键堆积（A-5 佐证）、共同关注 userId 裸数字（D-14 佐证）、100 人抢 50 库存零超卖零重复（A-1 修复不影响正确性基线）。

## 5. 学习清单

**核心知识点**
1. **副作用顺序**：幂等闸门/唯一索引终审必须**先于**不可撤销的副作用（DB 扣减），或副作用必须可补偿——"吞异常继续提交"是幻影扣减的标准成因。
2. **多步操作的事务边界**：条件关单三步的崩溃窗口分析——哪些步骤必须同事务、哪些可以提交后异步（Redis 派生态由对账裁决兜底）。
3. **兜底层自身的盲区**：对账只比对"存在性"就看不见"库存数量"漂移；挂单被翻牌一致是比对维度不足的经典案例。
4. **布隆过滤器写入顺序**：假阳性无害 vs 假阴性致命 → 先入布隆后写库。
5. **缓存竞争的工程解**：延迟双删关闭毫秒级窗口；完美 fencing（版本号）成本高，权衡后选低成本解并声明残留窗口。
6. **分层频控的错误码语义**：服务层 20001（同手机号）与切面层 40008（同 IP）并存是设计而非漂移——文档要写清"谁先拦"。
7. **fail-closed vs fail-open 的场景选择**：审核耗尽→强制驳回（错杀可申诉）；限流器故障→放行（不能成为故障点）；权限闸门→拒绝。

**面试必问题**
- Q：你如何发现这些缺陷的？——要点：五维审计（文档承诺→声明命令→主题核验→运行时黑盒→代码审查），交叉确认机制（两个独立审查路径发现同一 P1），运行时证据（148 键堆积/裸数字出参直接坐实文档失实）。
- Q：为什么撞唯一索引不直接抛异常回滚？——要点：回流场景用户已持有效单，异常穿透→Recoverer 回滚会误退真单资格；同事务补偿净效果为零。
- Q：延迟双删为什么是 1 秒？——要点：覆盖"重建任务查 DB→本提交交→重建 set 回旧值"的窗口；重建本身含 DB 查询+网络往返，秒级裕量；残留窗口（双删后仍有更晚的重建）由逻辑过期 30min 封顶。

## 6. 下一步

Web 前端线 W0（骨架+账户商户闭环）；若对外部署，开启 `admin-guard.enabled=true` 并配置白名单（deploy.md 同步说明）。
