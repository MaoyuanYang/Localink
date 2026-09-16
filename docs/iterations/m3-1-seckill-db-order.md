# 迭代任务卡：M3.1 秒杀 V1——纯 DB 下单

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.1 |
| 分支 | `feature/m3-1-seckill-db-order` |
| 状态 | 进行中 |

---

## 1. 目标

M3 秒杀核心链路第一棒：秒杀下单 V1——**纯数据库实现**，校验链（存在/上架/时间窗/等级/库存/一人一单）+ 扣库存 + 建订单全在一个事务里，刻意不做任何并发优化。这是铁律②"先暴露问题"的靶子版本：M3.2 用 JMeter 压测暴露它的竞态，再逐代演进（乐观锁 M3.2 → 分布式锁 M3.3~3.5 → Redis+Lua M3.6 → Kafka 异步 M3.8）。M1.8 预告的"时间窗/等级判定是 M3 下单链路职责"在本迭代兑现。

涉及表：`lk_voucher`（读）、`lk_seckill_voucher`（读 + 扣减）、`lk_voucher_order`（首次写入下单流量）；涉及接口：1 个（POST /api/seckill-voucher/{voucherId}/seckill）；涉及 Redis Key：无（V1 无缓存参与）。

## 2. 设计取舍

- **新建 VoucherOrderService 而非塞进 SeckillVoucherService**：订单域需要自己的家——M3.8 异步建单（消费端落库）、M5.4 超时关闭、W2 订单查询都长在这里；SeckillVoucherService 保持"秒杀券实体 CRUD"的单一职责。备选：把 seckill() 放 SeckillVoucherService（省一个类），但 M3 起该方法的复杂度（锁→Lua→MQ）会淹掉 CRUD 服务。普通券 claim() 仍留在 VoucherService（迁移重构不属于本迭代）
- **端点放 SeckillVoucherController：POST /api/seckill-voucher/{voucherId}/seckill**：资源主体是秒杀券，该控制器已按 voucherId 维度组织；返回订单 id 字符串（防前端 Long 精度丢失，照 M1.7 claim 先例）。备选 `/api/voucher/seckill/{id}`（hmdp 风格）——但本项目的券域控制器按资源拆分（Voucher/SeckillVoucher 两个），下单动作跟资源走更一致
- **扣库存用裸 UPDATE（`SET stock = stock - 1 WHERE voucher_id = ?`，无 stock > 0 守卫）**：铁律②刻意保留的竞态窗口①——"读判库存"与"扣减"分离，M3.2 压测的靶子。补一刀背景：hmdp 原版 stock 是有符号 int 能扣成负数，本项目建表（M1.1）选了 `int unsigned`，DB 层会以 1690 越界错误兜住数字超卖——竞态会以"异常风暴"而非"负库存"形态暴露，实验叙事在 M3.2 任务卡展开
- **一人一单用事务内 selectCount 查后插**：刻意保留竞态窗口②——同用户并发下"查"与"插"之间有缝，会插入多单；M3.5 用唯一索引兜底 + 分布式锁对比演示来修。database.md 明文"一人一单唯一索引不在 M1 建立"，本迭代不提前加索引
- **校验链顺序：存在/类型 → 上架 → 时间窗 → 等级 → 库存 → 一人一单**：先廉价后昂贵？本迭代全是单行/索引查询，成本差异可忽略，排序按"业务语义从静态属性到动态状态"组织（券是什么 → 能不能抢 → 够不够格 → 还有没有 → 抢过没有），可读性优先
- **等级校验用 UserHolder 会话里的 level**：TokenRefreshInterceptor 恢复会话时已带 level（M1.5 设计），免一次用户表查询；会话 level 与 DB level 的短暂不一致对门槛校验可容忍
- **订单 id 用 MyBatis-Plus ASSIGN_ID 雪花**：database.md 通用约定"M1 用 ASSIGN_ID，M4 起切自研 id-starter"，本迭代不引入 id-starter
- **新增 5 个错误码（10002~10006 券域段）**：秒杀的失败语义（未开始/已结束/库存不足/重复下单/等级不足）对前端是可展示文案，不是 40000 系统错误；分库分表（M4.5）与对账（M3.12）也依赖这些语义码做差异化处理

## 3. 产出物

| 文件 | 说明 |
|---|---|
| 接口 ×1 | POST /api/seckill-voucher/{voucherId}/seckill（登录，事务）→ Result\<String\> 订单 id |
| `BaseCode` +5 | 10002 SECKILL_NOT_STARTED / 10003 SECKILL_ENDED / 10004 SECKILL_STOCK_NOT_ENOUGH / 10005 SECKILL_DUPLICATE_ORDER / 10006 SECKILL_LEVEL_NOT_ENOUGH |
| `SeckillVoucherMapper` +deductStock | @Update 裸扣减（V1 无守卫，M3.2 加 `AND stock > 0`） |
| `VoucherOrderService` + 实现 | 新订单域服务：seckill(voucherId) 全校验链 + 扣减 + 建单 |
| `SeckillVoucherController` +1 端点 | 调用 VoucherOrderService.seckill |
| 测试 ×11 | 下单集成 ×9（happy path 建单字段+扣减+等级达标、未开始、已结束、库存 0、重复下单、等级不足、券不存在、普通券拒绝、下架拒绝）+ HTTP 层 ×2（无 token 40002、带 token 下单走通） |

## 4. 验证记录（2026-09-11 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy |
| 全量构建 | `./mvnw clean package "-DskipTests"` | BUILD SUCCESS |
| 测试 | `./mvnw test` | **135/135 通过**（+11：下单集成 9 + HTTP 2；基线 124） |
| 冒烟（业务链路） | curl 全链路 | 发码 → 登录 → 建秒杀券(stock=10) → **下单成功返回订单 id** → 同用户二单 **10005 每人限购一单** → SQL 置 stock=0 → **10004 库存不足** → 无 token POST **40002** → 对账 stock 10→9、订单 1 条 → 数据清理（用户/订单/券/Redis key 全 0 残留，8086 释放） |

**排障记录**：
- 现象：冒烟时 `redis-cli GET sms:code:{phone}` 读空，登录报验证码为空 → 根因：KeyBuilder 统一加 `lk:` 环境前缀（M2.2 Key 治理），裸 redis-cli 必须用 `lk:sms:code:{phone}` → 教训：绕过 KeyBuild 直连 Redis 观测时，先 `KEYS` 确认真实 key 形态再取值

## 5. 学习清单

**核心知识点**
1. **check-then-act 竞态**：V1 的三处读判（时间窗/库存/一人一单）都在事务内但都不加锁——事务保证"全成全败"（原子性），不消除"读与写之间的缝"（隔离竞态）；REPEATABLE READ 下快照读看到的是事务开始时的旧值
2. **unsigned 约束是 DB 层最后防线**：`int unsigned` 让 `stock - 1` 在 0 处抛 1690 越界错误，数字超卖被物理拦住——但防线越靠下层，失败形态越糟糕（系统异常 vs 业务拒绝），这正是"把校验前移"演进路线（乐观锁 M3.2 → Redis Lua M3.6 → 前置令牌 M3.15）的动机
3. **扣减用 UPDATE 表达式而非 select-then-update**：`SET stock = stock - 1` 是当前读 + 行锁的原子操作，并发 UPDATE 在该行上串行；竞态出在"应用层读判"与"DB 扣减"之间，不在 UPDATE 本身
4. **一人一单"查后插"的缝**：同用户并发事务都读到 count=0 都插入；修复三层演进——唯一索引兜底（DB）/分布式锁（串行化）/Lua 原子判断+插入标记（M3.5/M3.6）
5. **错误码语义化的价值**：10002~10006 是可展示、可分类的业务语义（前端文案、M3.12 对账按码归类），与 40000 系统错误的本质区别是"预期内的失败"
6. **校验链排序**：静态属性（存在/类型/上架）→ 动态状态（时间窗/等级/库存/重复），本迭代全是索引单行查询成本可忽略；M3.13 限流与 M3.15 令牌会把流量过滤再往前推

**面试必问题**
1. "纯 DB 秒杀为什么会超卖？"——画两个事务时间线：T1/T2 都读到 stock=1 都判通过，各自扣减建单；引出 check-then-act；追问"你们项目里真扣成负数了吗"——unsigned 兜住数字但暴露为异常风暴（M3.2 压测实录）
2. "加了 @Transactional 还会超卖吗？"——会；事务解决原子性与回滚，不解决并发可见性；隔离级别与锁各管什么
3. "一人一单怎么保证？"——V1 查后插的缝 → 唯一索引兜底 vs 分布式锁 vs Lua 原生的取舍（M3.5 实测对比）
4. "为什么不用 SELECT FOR UPDATE？"——悲观锁能防但行锁串行 + 事务持锁窗口长，吞吐崩；乐观锁 `WHERE stock > 0` 是 V1 的最小改动修复（M3.2）
5. "校验顺序为什么这么排？"——先拦流量大的（时间窗/库存），后做精确判断（一人一单要查订单表）；演进视角：限流/令牌前置后这条链越来越短

## 6. 下一步

**M3.2 JMeter 压测暴露超卖 → 乐观锁修复**：项目首次引入 JMeter（本机 5.6.3 + scripts/jmeter 资产入库），压 V1 下单接口——实验一不同用户并发（预期异常风暴 + unsigned 兜底实录）、实验二同用户并发（一人多单实锤，留 M3.5）、CAS 修复后复压（优雅失败 + 纯 DB 基线 QPS/RT 锚点，供 M3.6 对比）。
