# 迭代任务卡：M1.8 秒杀券 CRUD

| 字段 | 内容 |
|---|---|
| 迭代编号 | M1.8 |
| 分支 | `feature/m1-8-seckill-voucher` |
| 状态 | 待用户确认 |

---

## 1. 目标

M1 收官：秒杀券 CRUD——创建/更新/删除一次操作 lk_voucher(type=2) + lk_seckill_voucher 两表（**项目事务首秀**），查询聚合券面+秒杀属性；固化"秒杀券不走普通领取"边界。PRD F-VOC-01 秒杀券部分落地，M1 基础业务闭环完成。

涉及表：`lk_voucher`、`lk_seckill_voucher`；涉及接口：5 个（create/update/delete/detail/list）；涉及 Redis Key：无。

## 2. 设计取舍

- **@Transactional 项目首秀**：创建/更新/删除跨两表必须原子（半截数据=券面无扩展或扩展无券面）。LocalinkException 继承 RuntimeException，默认回滚规则天然覆盖业务异常。**M1.4 登记的 register 无事务债**：评估后保持现状——register 的 insert+update 第二步仅回填昵称，失败后果是昵称为空（可自愈的展示瑕疵），加事务收益不匹配改动面；本迭代立起事务规范，后续跨表写一律 @Transactional
- **时间约定落地**：beginTime/endTime 用 LocalDateTime + `@JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")`——architecture.md §5 时间约定首次在 API 层兑现。不用全局 `spring.jackson.date-format`：它只对 java.util.Date 生效，LocalDateTime 需要 @JsonFormat 或自定义序列化器，字段级注解最显式
- **库存双字段语义**：创建时 `init_stock = stock`；更新可设 stock 绝对值，**init_stock 创建后不可变**——它是 M3.6 预热/回滚回源的重建基准，可变会让"回源"失去锚点
- **status 仍手动管理**：上架/下架走更新接口；按时间窗判定"过期"是 M3 秒杀链路的职责（开抢前/结束后拒绝下单），M1.8 不引定时任务
- **秒杀券不走普通领取**：M1.7 claim 的 type≠1 防御（10001）本迭代用专门测试固化——秒杀下单是 M3 专属链路（前置令牌→Lua 原子扣减→Kafka 异步建单），普通领取通道必须对它关闭
- **VO 不含 initStock**：C 端视图只暴露 stock/minLevel/时间窗；重建基准是内部数据，不外溢
- **list 两段查询**：券表（shopId+type=2+上架）→ 扩展表 IN 批量，Map 聚合；避免逐券查扩展表的 N+1，也不为一对一聚合引入自定义 join SQL
- **无新增错误码**：复用 40001（时间顺序/非秒杀券/缺参）、40004（不存在）、10001（领取边界）

## 3. 产出物

| 文件 | 说明 |
|---|---|
| 接口 ×5 | POST/PUT/DELETE /api/seckill-voucher（登录，事务），GET /{voucherId}、GET /list?shopId=（公开） |
| `SeckillVoucherDTO`（api-model） | 券面 + stock @Min(1)、minLevel @Min(0)@Max(9)、beginTime/endTime @NotNull @JsonFormat |
| `SeckillVoucherVO`（api-model） | 聚合视图：voucherId/shopId ToString + 时间 @JsonFormat，不含 initStock |
| `SeckillVoucher` 实体 + Mapper | lk_seckill_voucher 首次落地 |
| `SeckillVoucherService` + 实现 | 双表事务 CRUD + 聚合查询 + 时间顺序/类型守卫 |
| `SeckillVoucherController` | @Validated @RequestBody |
| 测试 ×11 | CRUD×7（双表写入、聚合详情、更新保 initStock、双表删除、指向普通券 40001、时间倒挂 40001、list 只含上架）、领取边界×1（秒杀走普通 claim → 10001）、鉴权/校验×3（无 token 40002、缺 stock 40001、公开读放行） |

## 4. 验证记录（2026-08-18 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` + healthcheck | 双 healthy（构建前预检已成流程，本迭代零中间件故障） |
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 测试 | 同上 | **53/53 通过**（+11 秒杀域） |
| 冒烟（脚本化） | `powershell -File smoke-m18.ps1` | 建秒杀券（返回 id 字符串）→ 无 token 聚合详情（时间 `2026-08-20 10:00:00` 格式正确、stock=20/minLevel=2）→ 无 token list → 更新后 stock=35/minLevel=5/endTime 变更 → **普通 claim 被拒 10001** → 无 token 创建 40002 → 删除后 40004 → CLEANUP-OK 无残留 |

**排障记录**：本迭代无故障——构建前中间件预检（M1.5/M1.7 三次断连后固化的流程）直接规避了历史高发问题；冒烟脚本沿用纯 ASCII + `--data @file` 规范，一次通过。

## 5. 学习清单

**核心知识点**
1. @Transactional 机制：代理拦截 → 事务管理器绑定 ThreadLocal 连接 → 方法内多 SQL 同连接同事务；回滚规则默认只认 RuntimeException/Error；**自调用不经过代理不生效**（M1.4 任务卡同款考点）
2. 1:1 扩展表写入顺序：先父后子（子表 voucher_id 依赖父表生成的 id）；删除同序反向无强制，但同事务内两表俱删
3. 不可变字段设计：init_stock 作为重建锚点——分布式系统里"可回源的基准值"必须不可变，否则回源失去意义
4. Jackson 时间序列化：`spring.jackson.date-format` 只管 java.util.Date；LocalDateTime 走 @JsonFormat（字段级）或 JavaTimeModule 自定义（全局）——本项目选字段级显式
5. 一对一聚合查询三方案：两段查询+IN 批量（选用）vs 自定义 join SQL vs 逐条查询（N+1）——判据是数据量与 SQL 控制力的平衡

**面试必问题**
1. "秒杀券创建怎么保证原子性？"——@Transactional 双表同事务；业务异常是 RuntimeException 默认回滚；追问自调用失效与传播机制
2. "init_stock 和 stock 为什么分两个字段？"——stock 是实时可售，init_stock 是不可变基准；M3 Redis 预热/回滚回源都以 init_stock 为锚
3. "秒杀券为什么不能走普通领取接口？"——普通领取无库存扣减/时间窗/等级校验；秒杀是稀缺资源分配，必须走原子扣减链路（M3）；类型守卫在领取入口关闭通道
4. "结束时间过了券就自动下架吗？"——M1.8 不做（status 手动）；时间窗判定在 M3 下单链路做（运行时拒绝），定时任务扫 status 是可选优化不是正确性依赖

## 6. 下一步（M1 收官后）

**M1 基础业务闭环全部完成**（M1.1~M1.8）。解锁两条线，由用户选择先后：
- **M2 缓存体系**（后端主线）：M2.1 RedisCache 封装 → M2.2 Key 治理（偿还 M1.3 登记的临时 Key 常量债）→ M2.3 起商户缓存演进
- **W0 前端骨架**（前端线，前置 M1 已满足）：Vite 工程 + 登录页 + 商户列表/详情 + 普通券领取
