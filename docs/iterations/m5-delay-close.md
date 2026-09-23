# 主题任务卡：M5-B 延迟队列与订单超时关单

| 字段 | 内容 |
|---|---|
| 主题编号 | M5-B |
| 分支 | `feature/m5-delay-close`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-23） |

---

## 1. 目标

delay-starter 首批代码（Redisson 延迟队列分片 + 线程池消费 + 失败重投）与订单超时关单闭环
（延迟任务到期 → 条件关单 → DB/Redis 双库存回流 → 对账流水联动），覆盖原 M5.3+M5.4。

## 2. 主题内子任务

1. delay-starter：DelayQueuePublisher（分片投递）、DelayMessage 信封（重试计数）、
   DelayQueueConsumer 模板基类（每分片一消费线程/失败重投 3 次/耗尽告警）、自动装配（SmartLifecycle 统一启停）
2. 关单闭环：closeIfCreated 条件关单（幂等闸门）→ restoreStock DB 逆增量 → 复用统一回滚退 Redis 资格
3. 建单事务内投递延迟任务（orderId 分片，close-delay 可配默认 15m）
4. 对账联动补强：终态订单（取消/关闭）配未恢复流水 = 回流缺失差异 → 补偿（M5-A 语义的 M5-B 扩展）

## 3. 设计取舍

- **RBlockingQueue 无 ack 的可靠性三件套**：失败重投（退避 1s×3，计数在信封不占库表）→ 耗尽 error 告警丢弃
  → 业务幂等 + M5-A 对账兜底。take 即离队、进程崩溃即丢——关单丢一条的后果是晚关/不关，
  由对账"回流缺失"裁决最终收敛
- **分片的意义**：每分片一个阻塞消费线程，吞吐水平扩展；orderId 作 shardKey 同单恒定同片（FIFO 语义）
- **事务内投递**：事务回滚则任务空转，消费端条件关单（status=1 才关）天然幂等，空转无副作用——
  不为省一次空转引入 AFTER_COMMIT 复杂度
- **关单三步非原子的收敛设计**：条件关单/DB 回补/Redis 回滚跨存储无单事务——以"条件关单为闸门 +
  回滚幂等 + 对账裁决"收敛（rollback Lua 二次执行返回无需补偿，不双回）；这也是对账作为最终兜底层的语义扩展
- **StringCodec + fastjson2 信封**：Redisson 默认 JsonJackson codec 把 record 反序列化成 Map（消费侧解析炸），
  队列统一 StringCodec、信封显式 JSON——贴项目"Redis 全 String"纪律
- **close-delay 归"下单超时"（business_type=2）**：关单本就是超时语义，与 STALE_DROP 同类

## 4. 产出物

| 文件 | 说明 |
|---|---|
| `delay-starter` 首批代码 | Publisher（queueName 路由纯函数公开可测）/ DelayMessage / Consumer 基类 / AutoConfiguration（RedissonClient 互让先例 + SmartLifecycle） |
| `SeckillVoucherMapper` | +restoreStock（逆增量） |
| `VoucherOrderMapper` | +closeIfCreated（条件关单幂等闸门） |
| `VoucherOrderService(Impl)` | +closeOrderIfExpired（三步关单）；buildRestoreLog 映射 ORDER_CLOSE→下单超时；建单事务内投递 |
| `mq/DelayTopics` + `OrderCloseConsumer` | 队列名登记 + 薄消费适配 |
| `ReconciliationJob` | +settleAccordingToOrderState：终态订单流水未恢复 → 补偿（M5-B 联动） |
| KeyManage/yml | DELAY_QUEUE 文档登记；localink.delay.* + localink.order.close-delay |
| 测试 ×3（starter）+ ×2（server） | 到期送达/分片路由稳定+双 key 送达/失败重投第三次成功；关单全链路断言（status/close_time/DB/Redis/出集合/恢复行 bt=2）+二次幂等/取消态跳过 |

## 5. 验证记录（2026-09-23 本机实测）

starter 3/3、server 2/2；全 reactor **226/226**（221 基线 + 5），BUILD SUCCESS。

**排障实录（两坑都值得讲）**：
1. **关单三步竞态**：awaitStatus 等到 status=3 就断言,撞进"条件关单与回流之间"的窗口（两次运行 DB/Redis 断言随机炸）——
   现场日志定位后改为等"恢复行落库"（链路最后一步）消除竞态；并由此发现真缺口：**对账原语义会把"终态订单+未恢复流水"
   当一致翻牌**，补 settleAccordingToOrderState 裁决——测试暴露设计漏洞再反哺设计的完整案例
2. **多上下文 Kafka 消费竞争**（全量才现形）：多个缓存测试上下文共享消费组,建单消息被别的上下文
   以其 15m 默认配置投延迟任务,本类 500ms 任务根本不存在——server 测试改为直调 closeOrderIfExpired 断言业务闭环,
   延迟到期/分片/重投语义由 starter 测试覆盖（分层测试:框架管延迟语义,业务管关单正确性）

## 6. 学习清单

**核心知识点**
1. **RDelayedQueue 机制**：ZSet 扛时间 + 后台线程搬运 + 目标 BlockingQueue 扛顺序——到期判定在客户端侧
2. **无 ack 队列的可靠性设计**：重投+幂等+兜底三层,丢失代价与兜底层匹配（关单丢了=晚关,对账能收敛）
3. **条件更新做幂等闸门**：`UPDATE ... WHERE status=1` 一条 SQL 同时完成判定与执行,affected=0 即已处置
4. **跨存储三步的收敛模式**：无分布式事务时,以"闸门+幂等+对账裁决"替代原子性——每步可重入,终态收敛
5. **分片队列的 FIFO 边界**：同 shardKey 同片有序,跨片无序——选 key 即选有序性边界
6. **Spring 上下文缓存与共享资源**：同 JVM 多上下文共享 Kafka 组/Redis——测试属性隔离不了外部资源竞争

**面试必问题**
1. "订单超时关单怎么做的？"——建单投延迟任务(15m),到期条件关单(幂等闸门)→DB 逆增量→Redis 统一回滚退资格,失败落失败表+对账兜底
2. "延迟队列为什么用 Redisson 不用 RabbitMQ 死信/Kafka？"——同 Redis 栈运维小;讲 RDelayedQueue 三件套机制与无 ack 的代价
3. "关单和回流之间挂了怎么办？"——对账"终态订单+未恢复流水"裁决补回滚(幂等不双回)——能讲测试暴露这个洞的现场
4. "消息重复投递会不会双关？"——条件关单 affected=0 直接跳过,连库存都不会碰

## 7. 下一步

**M5-C 通知与运营统计**：开抢预通知（活动级延迟任务+人群圈选）、订阅通知闭环（ZSet 排队+取消回流自动发券）、
店铺每日 Top 买家（ZSet 统计+预通知附加圈选）——delay-starter 的第二批消费者。
