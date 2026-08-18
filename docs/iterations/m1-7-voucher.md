# 迭代任务卡：M1.7 普通优惠券 CRUD + 领取

| 字段 | 内容 |
|---|---|
| 迭代编号 | M1.7 |
| 分支 | `feature/m1-7-voucher-crud` |
| 状态 | 待用户确认 |

---

## 1. 目标

普通券业务闭环：券的增删改查（type 固定普通券）+ **领取接口**（生成订单，订单实体首次落地），为 W0 前端"商户详情 + 普通券领取"（F-WEB-C02/C03）备齐 API。PRD F-VOC-01 普通券部分落地。

涉及表：`lk_voucher`、`lk_voucher_order`；涉及接口：6 个（list/detail/create/update/delete/claim）；涉及 Redis Key：无。

## 2. 设计取舍

- **领取接口纳入 M1.7**：roadmap 后续迭代无"普通券领取"的安放位置，而 W0 前端闭环（F-WEB-C03）依赖它；领取是普通券的业务闭环，与 CRUD 同迭代交付。经用户确认
- **type 不进 DTO**：M1.7 只造普通券，service 固定 type=1；M1.8 秒杀券走独立 DTO/接口（voucher type=2 + lk_seckill_voucher 扩展表）。一个 DTO 不背两种业务语义，创建入口即类型约束
- **公开 list 仅返回上架券（status=1）**：C 端不应见下架券；运营全量视图属 W2 后台职责，届时另开接口，不往公开接口塞 filter
- **领取校验链**：券存在（40004）→ type=1 且 status=1（否则 10001 VOUCHER_NOT_AVAILABLE）→ 建订单。type 校验是防御性的——M1.8 后库中会有秒杀券，秒杀必须走 M3 链路而非普通领取
- **可重复领取**：普通券无库存概念（库存语义在秒杀扩展表），每次领取=一条独立订单；"一人一单"是秒杀场景约束（M3 主题），不提前引入。经用户确认
- **订单实体（VoucherOrder）首次落地**：status=1 已创建、reconciliation_status=1 待处理——对账状态独立于业务状态字段，M3.12 对账体系直接复用，不混状态机
- **删券不检查关联订单**：订单是历史事实不级联删除；运营的正当操作是下架而非删除（对照 M1.6 类型占用守卫：那里防孤儿展示数据，这里孤儿订单对演示无害）
- **shopId 不做存在性校验**：沿用 M1.6 逻辑外键约定；孤儿券不会出现在任何商户的 list 中，无害
- **券域错误码 1xxxx 开篇**：VOUCHER_NOT_AVAILABLE(10001)；architecture.md 分段标签"1xxxx 秒杀券"拓宽为"券域（普通/秒杀）"——原标签过窄，属文档修正

## 3. 产出物

| 文件 | 说明 |
|---|---|
| 接口 ×6 | GET /api/voucher/list?shopId=（公开，仅上架）、GET /{id}（公开）、POST/PUT/DELETE（登录）、POST /{id}/claim（登录） |
| `VoucherDTO`（api-model） | shopId @NotNull、title @NotBlank、payValue @Min(0)、actualValue @Min(1)、status 可选默认上架 |
| `VoucherVO`（api-model） | id/shopId ToStringSerializer + createTime |
| `Voucher` / `VoucherOrder` 实体 | 订单实体首次落地（lk_voucher_order 全字段映射） |
| `VoucherMapper` / `VoucherOrderMapper` | BaseMapper |
| `VoucherService` + 实现 | CRUD + claim（UserHolder 取当前用户建单） |
| `VoucherController` | @Validated @RequestBody |
| `BaseCode` + `architecture.md` | 10001 + 券域标签修正 |
| 测试 ×11 | CRUD×4（type/status 默认值、list 只含上架、删除、缺 id）、领取×3（建单字段正确、重复领取第二单、下架拒领 10001）、鉴权/校验×4（无 token 领取/创建 40002、缺 title 40001、公开读放行） |

## 4. 验证记录（2026-08-18 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 测试 | 同上 | **42/42 通过**（+11 券域） |
| 冒烟（脚本化） | `powershell -File smoke-m17.ps1` | 登录 → 建券返回 id 字符串 → 公开 list（集成测试已确定性断言可见）→ **领取×2 得两个不同订单 id** → 下架 → 领取被拒 10001 + list 隐藏（visible=0）→ 无 token 创建 40002 → 删券后详情 40004 → CLEANUP-OK 无残留 |

**排障记录**：
1. **构建首败再次全灭于中间件断连**（Redis/MySQL 双连不上）——机器重启后 Docker Desktop 未自启，与 M1.5 同因。恢复路径已固化：启 Docker Desktop → 轮询 daemon → `compose up -d` → 双 healthy → 重跑全绿。这是第三次出现，**结论：构建全灭先查中间件**已是稳定经验
2. **冒烟第 4 步断言表达式缺陷**：`($list | Where {...}).Count` 在 PS 5.1 中匹配到单个对象时输出空（单对象无 Count 属性），空匹配时才输出 0——表达式选错了。可见性由集成测试 `listReturnsOnlyOnShelf`（创建后可见/下架后不可见双断言）确定性覆盖，冒烟第 7 步的下架隐藏检查（输出 0）亦佐证

## 5. 学习清单

**核心知识点**
1. 单表多类型建模：type 字段区分券种 + 扩展表承载类型专属属性（库存/时间窗）——垂直拆分的边界判据（专属字段占比与热点隔离）
2. 订单双状态字段设计：业务状态（status）与对账状态（reconciliation_status）分离——一条订单的业务生命周期与一致性核对是两条独立关注线，混在一个状态机会互相污染
3. 领取语义的业务分化：普通券可重复（无库存约束）vs 秒杀一人一单（稀缺资源）——相同动作不同规则，规则来自资源属性而非动作本身
4. 列表接口的视图职责：公开 list 是"C 端视图"而非"数据导出"，状态过滤在服务端完成，前端不做业务过滤
5. 读写分离约定的复利：M1.6 定下的约定本迭代零修改直接复用——好的机制是一次投资持续收益

**面试必问题**
1. "普通券和秒杀券怎么建模？"——共用券面表（type 区分）+ 秒杀扩展表（库存/时间窗/等级门槛 1:1）；专属属性隔离，热点行独立，M3 演进只碰扩展表
2. "订单表为什么有个对账状态字段？"——业务状态与一致性状态分离；Redis/Kafka/DB 分布式链路下，对账是独立关注线（M3.12 三层对账的基础）
3. "普通券为什么能重复领取？"——无库存约束，领取=生成订单；一人一单是稀缺资源（秒杀库存）的分配规则，不普适
4. "删除一张已被领取的券会怎样？"——订单保留（历史事实），券消失；运营规范是下架而非删除——演示项目接受，生产可加软删

## 6. 下一步

M1.8 秒杀券 CRUD：voucher type=2 + lk_seckill_voucher 扩展表（init_stock/stock/begin_time/end_time/min_level），创建接口一次写两表（事务），查询接口聚合券面+秒杀属性——M1 收官。
