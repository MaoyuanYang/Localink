# 主题任务卡：W1 秒杀演示（倒计时 + 两步流 + 我的订单）

| 字段 | 内容 |
| --- | --- |
| 主题编号 | W1（Web 前端线第二主题） |
| 分支 | `feature/w1-seckill`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-30） |

## 1. 目标

把 M3 秒杀全链路（令牌申请 → Lua 原子扣减 → Kafka 异步建单）与 M5-B 订单生命周期（15 分钟超时关单）以可操作页面呈现：秒杀券详情页（阶段自算倒计时 + 抢购按钮状态机 + 两步流令牌）+ 我的订单页。含一个后端补口：订单分页查询接口（W0 任务卡 §8 预告的缺口——全部 14 个 Controller 无任何订单查询端点）。前置 M3/M5 已就绪，不维护 mock。

## 2. 主题内子任务

1. **后端订单查询**：`VoucherOrderVO`（api-model）+ `GET /api/order/page?page=&size=`（OrderController）+ `VoucherOrderService.pageMyOrders`（分片路由按 user_id 单库、表广播归并；title 批量回填）。
2. **前端秒杀详情页 `/seckill/:id`**：阶段自算（未开始/进行中/已结束/已下架）+ 秒级倒计时 + 两步流按钮（申请令牌 30s 倒计时 → 立即下单）+ 错误分支反馈。
3. **前端我的订单页 `/orders`**：分页列表（券名回填/类型/状态三态/时间），未登录 40002 自动跳登录。
4. **商户详情页秒杀区**：`/api/seckill-voucher/list` 渲染活动卡片（阶段 tag），入口进秒杀详情。
5. **导航/路由**：AppLayout 加"我的订单"，router 注册 `seckill/:id`、`orders`。

## 3. 设计取舍

- **活动阶段前端自算 = 后端详情接口本就只返回 beginTime/endTime**：阶段（未开始/进行中/已结束）不是字段也不实时计算，后端仅在**下单时**校验窗口（10002/10003）；前端按本地时钟推倒计时，同机部署（演示口径）无时区漂移。若分秒级边界竞争，以后端下单校验为准——前端倒计时只是 UI 预判。
- **两步流拆两个按钮 = 让"令牌"这一 M3 叙事可见可演示**：合并成"一键抢购"（自动 token→seckill）体验更顺，但压测叙事里令牌机制（USER 3/min 限流、30s 一次性、10007 无效）就看不见了。拆开后可单独演示：只申请令牌不下单、令牌过期后下单吃 10007。
- **订单接口 Service 显式判登录 = 拦截器的 GET 盲区**：LoginInterceptor 只拦非 GET 与 `/api/user/**`，`GET /api/order/page` 匿名可达；不在拦截器加白名单维护（改全局规则影响面大），Service 首行 `UserHolder.get()==null → 40002` 收口。
- **title 后端回填 vs 前端二次查券**：订单列表要显示券名，前端拿 voucherId 逐个调券接口是 N+1；后端分页后按 voucherId 集合一次查 lk_voucher 主库回填（先判空防 M6-E 的空 IN() 坑）。
- **订单含普通券+秒杀券全查**：统一订单表两类 voucher_type 都有，"我的订单"同时承接 W0 领券闭环与 W1 秒杀闭环，页面用 tag 区分。
- **异步落库容忍**：seckill 接口返回 orderId 时订单尚未落库（Kafka 消费端 <1s 落库）；前端下单成功后跳订单页即查可能瞬时空，页面提供刷新/重查，不做前端轮询（演示口径数据量小，刷新即可见）。
- **测试不开 properties 独立上下文**：W0 排障 1 教训（新增独立上下文挤爆 MySQL 151 峰值），新测试类用默认共享上下文。

## 4. 接口 / 配置

W1 消费/新增的接口：

| 接口 | 方法与路径 | 说明 |
| --- | --- | --- |
| 秒杀券详情 | GET `/api/seckill-voucher/{id}` | SeckillVoucherVO（stock/minLevel/beginTime/endTime `yyyy-MM-dd HH:mm:ss`）；阶段前端自算 |
| 店铺秒杀列表 | GET `/api/seckill-voucher/list?shopId=` | List，type=2 且上架，商户详情页秒杀区 |
| 申请令牌 | POST `/api/seckill-voucher/{id}/token` | data=UUID；30s 一次性覆盖式；IP 10/min、USER 3/min（40008） |
| 秒杀下单 | POST `/api/seckill-voucher/{id}/seckill?token=` | data=orderId（异步落库 <1s）；10002/10003/10004/10005/10006/10007 各失败分支 |
| **我的订单（本主题新增）** | GET `/api/order/page?page=&size=` | Page\<VoucherOrderVO\>（title 回填、status 1 已创建/2 已取消/3 超时关闭）；需登录（Service 判 40002）；size 钳 50 |

新增配置：无（订单查询无开关，登录态即权限边界——只查本人）。

## 5. 产出物

| 文件 | 说明 |
| --- | --- |
| `localink-api-model/.../vo/VoucherOrderVO.java` | 订单 VO：id/userId/voucherId（字符串化）+voucherType/status/title/createTime/closeTime |
| `localink-server/.../controller/OrderController.java` | GET /api/order/page |
| `localink-server/.../service/VoucherOrderService.java`（+impl） | pageMyOrders：钳制/eq userId/orderByDesc id/title 批量回填 |
| 测试 `OrderPageIntegrationTest.java` | 未登录 40002；造单断言分页/回填/排序（默认上下文） |
| `localink-web/src/api/seckill.ts` / `order.ts` | 秒杀四接口 / 订单分页 |
| `localink-web/src/types/api.ts` | +SeckillVoucherVO / VoucherOrderVO |
| `localink-web/src/pages/SeckillDetailPage.tsx` | 阶段状态机 + 倒计时 + 两步流 |
| `localink-web/src/pages/MyOrdersPage.tsx` | 订单分页列表 |
| `localink-web/src/pages/ShopDetailPage.tsx`（改） | 秒杀区卡片 |
| `localink-web/src/components/AppLayout.tsx` + `router/index.tsx`（改） | 我的订单导航 + 路由 |
| `docs/roadmap.md` / `README.md` | W1 勾选、基线更新 |

## 6. 验证记录（2026-09-30 本机实测）

**测试基线 302 → 305/305**（server 202→205，新增 OrderPageIntegrationTest 3：未登录 40002 / 造三单断言分页倒序回填关闭态 / size 钳 200→50），`mvnw clean test` 全 reactor 串行 BUILD SUCCESS。前端 `tsc && vite build` 通过（22s）。

**GUI 联调冒烟**（jar 8086 带 dev 开关 + vite；两场自造活动：进行中 begin=-1h/end=+1h stock=10、未开始 begin=+5min stock=5），清单全部通过：

1. ✅ 商户详情页秒杀区渲染 3 场活动（含 1 场 M4 历史"已结束"），阶段 tag 全部正确（已结束/进行中/未开始）
2. ✅ 未开始场次：距开始倒计时滚动（00:02:16 实拍）、两按钮均禁用
3. ✅ 进行中场次：第一步申请令牌 →"令牌已发放，30 秒内有效"（剩余秒数实时递减）→ 第二步立即抢购 → 抢购成功 → 1.2s 后自动跳 /orders → 订单列表显示"W1冒烟-进行中场次 [秒杀 tag]、订单号 98669613826486272、已创建"——**券名后端回填生效**
4. ✅ 重复抢购：第二次申请令牌（USER 3/min 限流内）→ 下单 → toast"每人限购一单"（10005）；页面库存 10→9 可见扣减
5. ✅ 未登录访问 /orders → 40002 → 自动跳 /login
6. ✅ 造普通券 API 冒烟：GET /api/order/page 返回 Page 结构（size 钳制 HTTP 层验证同测试类）

**冒烟数据已清扫**：两场活动券（lk_voucher + lk_seckill_voucher）、1 笔订单（ds_1.lk_voucher_order_1 按 id 物理表删）、冒烟用户、7 个 Redis key（stock/flow/notice/order/幂等标记/用户通知）全部删除复查归零；限流滑动窗口 key 60s TTL 自灭；M5 历史遗留 notice:sent 系列（非本次产物）按既定决策未动。另顺手清理 10 张 M4/M8 时代"无活动记录的孤儿秒杀券"（lk_voucher type=2 但 lk_seckill_voucher 无行——W1 秒杀区会将其暴露为脏数据）。

**排障记录**：

1. **5173 被僵尸 vite 占用**：W0 会话 TaskStop 杀 npm 未杀 node 子进程（仅绑 [::1]），W1 新 vite 落到 5174；经 5173 的请求打到旧实例出现"API 返回 index.html"怪象。处置：taskkill 旧 PID + 冒烟改走 5174。教训：停 dev server 应杀进程树。
2. **秒杀价展示语义放反**：初版把券面值 actualValue 当"秒杀价"大字、支付价 payValue 当"原价"划线，变成"花 200 买 20 的券"；联调实拍发现。修正：大字=支付价 payValue、括注券面值 actualValue（秒杀区同步改"¥X 抵 ¥Y"）。payValue=花的钱、actualValue=抵扣面值，与后端 DTO 校验"支付金额必须小于抵扣金额"对齐。
3. **孤儿秒杀券暴露**：历史测试在 lk_voucher 留有 type=2 但无 lk_seckill_voucher 活动行的券，秒杀列表返回其 beginTime=null，前端 dayjs 解析 NaN 比较全 false 会误判"进行中"。双保险：前端 phaseOf 对无效时间返回 'off'（Number.isFinite 防御）；DB 侧删除 10 张孤儿券。
4. **造活动 40001"支付金额必须小于抵扣金额"**：初版把 payValue/actualValue 传反（payValue=10000 > actualValue=1000 触发校验）；与排障 2 同根——先被后端校验拦下，页面展示错误则是联调才发现，两处印证同一语义。
5. **测试断言假设"券 id=1 存在"失败**：OrderPageIntegrationTest 初版复用 voucherId=1 断言 title 回填，实测 lk_voucher 无 id≤10 的记录（历史券全是雪花 id）；改为全部自造券。
6. **-pl 单模块跑不进 reactor 的 api-model 变更**：`-pl localink-server` 不带 `-am` 时用到本地仓库旧 api-model jar，新增 VO 类编译失败；带 -am 后通过（W0 已踩过同型，此处为忘带 -am 变体）。

## 7. 学习清单

**核心知识点**

1. **两步流令牌的前端语义**：令牌不是"资格"而是"削峰闸门"——30s 一次性 UUID 把瞬时点击打散成两段可限流的请求（token 接口 USER 3/min 限流在前，真正扣减在后）；前端拆两按钮把这段叙事变成可演示的交互。
2. **异步建单的最终一致在前端的投影**：seckill 返回 orderId ≠ 订单已存在（Kafka 消费端落库 <1s），"我的订单"页要容忍瞬时空列表——后端已用对账日志 traceId 串联全链路，前端只需提供重查。
3. **分片表按 user_id 查询的路由行为**：库键 user_id%2 命中单库，表键 voucher_id 缺失则库内两表广播归并；uk_user_voucher_active 最左前缀 user_id 是 M4 设计时就为"我的订单"预留的索引（database.md §4.6）——查询模式在分表设计时已埋好。
4. **拦截器 GET 盲区**：登录拦截只覆盖非 GET 与 /api/user/**，"读接口也要登录"必须在业务层显式收口——安全边界不能默认继承全局规则，要按接口逐一确认。
5. **订单状态三态与延迟关单**：status=1 已创建是暂时态，15 分钟未支付由延迟队列关单置 3（同时回补库存回流发券）——订单页的"已创建"是一个倒计时中的状态，不是终态。

**面试必问题**

1. "秒杀为什么拆两步（先申请令牌再下单）？"——同步链路只做"轻校验+发令牌"（限流在门前），重扣减在第二步且必须带有效令牌；令牌 30s 一次性=把流量整形出两个可分别限流的漏斗，Redis Lua 在第二步原子完成"判库存+判重+扣减+流水"。追问"令牌能防黄牛吗"→不能，它防的是瞬时洪峰；一人一单靠 Lua 判重+唯一索引兜底。
2. "前端怎么处理异步建单的延迟？"——下单接口返回 orderId 时 Kafka 消费端可能尚未落库；前端跳订单页允许短暂查不到（刷新即可见），不做轮询重试——本地 broker <1s 落库，权衡演示复杂度。追问"生产怎么做"→下单返回带状态查询端点，前端按 orderId 轮询/推送查终态（成功/库存不足回滚）。
3. "你的订单表分片了，按用户查订单怎么路由？"——user_id 是库分片键，WHERE user_id=? 精确路由单库；表键 voucher_id 不在条件里则该库两张表广播归并，MyBatis-Plus 分页由 ShardingSphere 归并 count；索引最左前缀 user_id 早已预留。

## 8. 下一步

W2 运营后台：/admin 布局 + 登录守卫 + 商户/券管理 + 订阅提醒 + 订单状态（超时关闭）展示（前置 M5 已就绪；admin-guard 开关演示口径随 W2 定）。
