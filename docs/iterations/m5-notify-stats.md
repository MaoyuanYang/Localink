# 主题任务卡：M5-C 通知与运营统计

| 字段 | 内容 |
|---|---|
| 主题编号 | M5-C（M5 阶段收官） |
| 分支 | `feature/m5-notify-stats`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-23） |

---

## 1. 目标

通知与运营三件套（原 M5.5~5.8）：开抢预通知（活动级延迟任务+人群圈选）、订阅通知闭环
（排队/状态/回流自动发券）、店铺每日 Top 买家（ZSet 统计+预通知附加圈选）。
delay-starter 迎来第二批消费者。

## 2. 主题内子任务

1. 预通知：券创建投活动级延迟任务（beginTime−lead）→ 到期 SETNX 闸门 → 回查事实源 →
   等级圈人+Top 附加 → 写用户收件箱（ZSet）→ 查询接口
2. 订阅：三端点（订阅/取消/状态）+ 重复订阅保持首刻排队位 + 回流自动发券
   （popMin 原子弹最早 → 发 Kafka 建单消息 → 状态 GRANTED）
3. Top 买家：建单事务内 ZINCRBY 按日记账 + 降序查询端点 + 预通知附加圈选
4. cache-starter：RedisZSetOps 补 `popMin` API（Spring Data Redis 原生 ZPOPMIN）

## 3. 设计取舍

- **"闹钟挂在活动上，响时查名单"（面试口径的代码兑现）**：延迟任务只有一条（voucherId），到期回查
  事实源（券在/在架/未开场）再圈名单——绝不给每个用户投任务（十万订阅者=到期风暴）。用户的"资格"
  是到期时查 DB 算出来的，不是预先投出去的
- **SETNX 防重闸门在前、过期裁决在后**：延迟任务重投不重发（闸门先行）；开场容差 5min 外的迟到
  通知无意义直接跳过（跳过也占标记——该券本就不该再发，无害）
- **自动发券 = 替订阅者走异步建单**：发 SeckillOrderMessage 即继承消费端全套（幂等/流水/路由/
  超时关单任务）——不为发券新写一条建单路径。补位单再超时→回流→弹下一位，**链式补位是正确语义**
- **popMin 的原子性**：弹出与移除无并发缝隙——两个回流同时发生也只各弹一人，不会同一人被发两次
- **Redis 库存漂移声明**：发券走 DB 扣减不扣 Redis 预热值（Redis 显示+1）——由自然扣减对齐/
  活动 TTL 清理/对账回灌的既有口径兜底；漂移方向是"多显示"（少卖不会超卖），安全侧
- **不建任何表**（database.md 第 6 节 #8 既定）：订阅排队/状态/收件箱/Top 全 Redis 结构,
  重启恢复="DB 事实源重算或接受丢失"逐场景定义
- **Top 按日 key+TTL 2 天**："每日"语义由 key 日期段承载，过期自清免维护任务

## 4. 产出物

| 文件 | 说明 |
|---|---|
| `RedisZSetOps` + Default | +popMin（ZPOPMIN 原子） |
| `mq/SeckillNoticeConsumer` | SETNX 闸门/回查/等级圈人+Top 附加/收件箱群发 |
| `SeckillVoucherServiceImpl.create` | +offerPreNotice 投递点（lead-minutes 可配） |
| `service/SubscribeService(Impl)` | 三操作+tryGrantEarliest（popMin→发券→GRANTED） |
| `service/TopBuyerService(Impl)` | recordOrder（ZINCRBY 按日）/topBuyers（降序） |
| `VoucherOrderServiceImpl` | 关单回流后 tryGrantEarliest；建单事务内 Top 记账 |
| `controller/NoticeController`、`SubscribeController` | GET /api/notice；订阅三端点+GET /api/shop/{id}/top-buyers |
| KeyManage | +USER_NOTICE/SUBSCRIBE_QUEUE/SUBSCRIBE_STATUS/SHOP_TOP_BUYERS/NOTICE_SENT 五条 |
| 测试 ×5 | 预通知圈人（等级+Top 附加+不达标拒）/sent 防重+过期跳过/订阅三态+首刻保持/回流自动发券（最早者补位+GRANTED+剩一人）/Top 降序 |

## 5. 验证记录（2026-09-23 本机实测）

主题 5/5；全 reactor **231/231**（226 基线 + 5），BUILD SUCCESS。

**关键断言**：level≥minLevel 用户与 Top 买家（等级不达标）收到预通知、低等级不收；sent 标记拦截
重投不重发、beginTime 已过的券任务被开场容差跳过；重复订阅 score 不变（首刻排队位）；关单回流后
最早订阅者自动补位建单（status=1）+GRANTED、队列剩 1 人且后来者仍 SUBSCRIBED；Top 两单者居首。

**排障记录**：①圈人查 DB 事实源而断言误用会话等级（UserHolder≠DB level）——改为"未达标不收"
断言，圈人逻辑本身正确；②发券用例的券未开场（beginTime 未来）秒杀被拒——用例改用已开场券。

## 6. 学习清单

**核心知识点**
1. **事件级 vs 用户级延迟任务**：闹钟挂事件（一条），响时查名单（DB 重放）——用户级任务在十万人
   场景就是到期风暴；"订阅"只是到期时查的名单,不是预投的任务
2. **通知的幂等三闸门**：SETNX 已发标记（防重投重发）→ 回查事实源（券态变了不发）→ 开场容差
  （迟到太久不发）——通知宁少勿滥
3. **补位的链式语义**：订阅者补位单再超时→回流→弹下一位——看似递归实为正确排队推进
4. **popMin 原子出队**：ZSet 排队+弹出一步原子,并发回流不会同人双发——"最早订阅者"的并发安全实现
5. **复用即设计**：自动发券零新代码路径（复用 Kafka 建单全套）,新增的只是"谁触发"——好架构让新场景
   只写触发器不写执行器
6. **按日 key 的榜单**：日期入 key+TTL 自清,"每日"无需任何定时任务

**面试必问题**
1. "秒杀开始前怎么通知用户？"——活动级延迟任务（一条）响时查名单圈人（等级+Top 附加）写收件箱；
   追问"为什么不给每人投延迟消息"→到期风暴,讲"闹钟挂活动上"的口径
2. "售罄后订阅、有人退单怎么办？"——ZSet 排队,回流时 popMin 原子弹最早订阅者,替他走异步建单
  （复用全套）,状态 GRANTED;追问"补位的单又超时"→链式弹下一位,语义正确
3. "通知会不会重复发？"——SETNX 闸门+回查+容差三闸门,宁少勿滥
4. "每日 Top 买家怎么做的？"——建单事务 ZINCRBY 按日 key,TTL 自清;重启丢失由订单重算

## 7. 下一步

**M6 社区扩展**（六个主题）：M6-A 内容基础（图片上传/帖子/评论）→ M6-B 互动 → M6-C Feed →
M6-D 搜索 → M6-E 热点 → M6-F 风控与特色。
