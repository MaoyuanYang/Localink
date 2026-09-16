# 迭代任务卡：M3.8 秒杀 V2②——Kafka 异步下单

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.8（B6 批次） |
| 分支 | `feature/m3-8-seckill-async` |
| 状态 | 已完成（2026-09-16） |

---

## 1. 目标

MQ 框架（M3.7）的第一个业务用户：seckill 改为"校验 → Lua 扣减 → 同步发消息 → 立即返回预生成 orderId"，建单由消费端异步完成——解开 M3.6 exp3 暴露的同步建单瓶颈（QPS 74.9 / P99 1944ms）。

## 2. 设计取舍

- **orderId 预生成随消息下发，API 响应契约不变**：MP 自带 `IdWorker.getId()`（雪花）在发送前生成、消息携带、消费端按该 id 落库——用户即时拿到真实订单号（可轮询订单状态）。M4.1 自研 id-starter（时钟回拨 + Redis workId）落地后一处替换。备选"返回受理号再换订单号"——契约复杂化，否
- **请求路径 sendSync（同步发送）而非纯异步**：本地 broker + acks=all 约 1~3ms，换确定语义——**发送失败 = 下单失败 + Redis 已回滚 + 用户可重试**（40006）。纯异步的"响应已出、发送后失败"补偿复杂度留给 M3.11。QPS 大头在摆脱同步 DB 事务，不在 Kafka 往返
- **@Transactional 从 seckill() 移除**：请求线程不再有任何 DB 写——事务随建单逻辑一起搬到消费端 `createSeckillOrder(msg)`
- **消费端幂等守卫 = orderId 查存在即 ack**：at-least-once 必然重投（ack 丢失、重平衡），无守卫会 DuplicateKey 异常循环重投直至耗尽。守卫 + 唯一索引兜底（守卫与插入间隙的极窄竞态由 DuplicateKeyException 转成功吸收）——M3.10 @RepeatExecuteLimit 的手工雏形
- **建单逻辑留在 VoucherOrderService（新公开方法），消费者只做薄适配**：业务归 service、接线归 consumer——M3.11 给消费端加重试/回滚时不动业务
- **消息 key = voucherId**：同券同分区（局部性 + 单分区内有序，幂等测试的 FIFO 论证依赖此性质），跨券并行
- **消费失败的 Redis 回滚刻意不做（M3.11）**：立即回滚 + 重投会超卖（回滚把资格还给用户，重投又建一单）；正确姿势是"重试耗尽才回滚"，需要定制 DefaultErrorHandler。本批边界：重投耗尽后丢弃 + error 日志，差异由 M3.12 对账兜底

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `mq/MqTopics` + `mq/SeckillOrderMessage` | topic 常量登记（与 yml 镜像）；record 消息（orderId/voucherId/voucherType/userId） |
| `mq/SeckillOrderConsumer` | 薄适配：@KafkaListener → dispatch → 委托 service |
| `VoucherOrderService` +createSeckillOrder | 消费端建单（orderId 守卫 + CAS + insert + DuplicateKey 吸收），@Transactional |
| `VoucherOrderServiceImpl.seckill` | 去 @Transactional/去 DB 写；预生成 id + sendSync + 失败回滚 Redis |
| application.yml | localink.mq.topics.seckill-order（分区 3） |
| 测试 ×2 + 适配 | 异步落库字段断言；重复消息守卫（同 key 尾随标记消息 FIFO 论证）；既有 13 用例 DB 断言全部加 await（OrderAwait 工具） |

## 4. 验证记录（2026-09-16 本机实测）

全量 **166/166**（164 基线 + 2 新增）。

### JMeter 对照（exp3 同场景：400 用户充足库存全成功）

| 指标 | M3.6 同步建单 | **M3.8 异步建单（冷启）** | **M3.8 热身复跑** |
|---|---|---|---|
| QPS | 74.9 | 整体 85.7（**峰值冲刺 847/s**，首个请求 3.1s 为 Kafka 生产者/JIT 冷启动） | **168.4** |
| Avg RT | 1257ms | 1499ms（含冷启长尾） | **233ms（-81%）** |
| P99 / Max | 1944 / 1951ms | 2678 / 3137ms | ~500 / 514ms |
| 错误 | 0 | 0 | 0 |
| 最终一致 | 即时 | 轮询确认 400 单落库、DB=Redis=9600 | 同左 |

### 正确性回归

- exp1-async（300 用户抢 100）：100 单 / 100 distinct users / DB=Redis=0，失败 156×10004 + 44 连接被拒（accept 队列，老朋友），零系统异常
- exp2-async（同用户 200 并发）：恰 1 单 + 145×10005 + 54 连接被拒
- 重复消息守卫测试：同 orderId 消息重投被守卫忽略（用同 key 尾随标记消息的分区 FIFO 性质做确定性论证，不靠 sleep）

**归因（下一切口的锚点）**：成功路径 QPS 168 的剩余瓶颈 = 校验链 2 次 DB SELECT（Hikari=10 排队）——与 exp1 拒绝路径 Avg ~1.1s 同源。演进路径：券详情缓存化 / M3.13 限流 / M3.15 前置令牌逐代前移校验链。

**排障记录**：
1. **一次假绿（流程教训）**：全量跑完 grep "FAILURE!" 无输出 + surefire 汇总 0 失败 → 误判全绿；实际是**编译失败**（漏 assertTrue import），报告来自上次的陈旧产物。此后验证必须以 `BUILD SUCCESS` 行 + 用例数增量双确认。教训：**验证脚本要盯退出链路（编译也算），不能只看测试报告**
2. **受检异常两连**：OrderAwait 抛 InterruptedException，三个既有用例没声明 throws——加；lambda 以 Runnable 提交不收受检异常——Callable 化（M3.2 同款）

## 5. 学习清单

**核心知识点**
1. **异步化的本质是"移走长事务"**：请求线程只剩 校验(读) + Lua(Redis) + 发消息(Kafka) 三段短操作，DB 事务整体搬到消费端——Avg 1257→233ms 的来源不是某个操作变快，而是**等待的东西变了**
2. **orderId 预生成的价值**：异步系统里"响应"和"落库"分离，预生成 ID 让两者共享同一标识——客户端可轮询、幂等守卫可按键判重。分布式 ID 是异步架构的粘合剂
3. **at-least-once 下的消费幂等**：重投不可避免（ack 丢失/重平衡），守卫（查存在）+ 唯一索引（兜竞态间隙）双层吸收——"消息框架给不了 exactly-once，业务自己给"
4. **补偿的时机学**：请求路径失败→立即回滚（用户还没拿到响应，语义干净）；消费路径失败→等重试耗尽再回滚（立即回滚+重投=超卖）。**同一个回滚动作，时机错了他就是 bug**
5. **分区 key 与局部有序**：key=voucherId → 同券消息同分区 FIFO；测试用"尾随标记"确定性证明前序消息已处理——把 Kafka 的有序性当测试工具用
6. **冷启动长尾**：首次请求 3.1s（生产者建连+JIT）——压测报告要区分冷启/热身，单次全量数字会骗人（本批冷/热两跑的教训）

**面试必问题**
1. "异步下单用户怎么拿到订单号？"——预生成雪花 ID 随消息下发，响应契约与同步版一致；客户端可轮询状态
2. "消息重复消费怎么办？"——orderId 守卫查存在即 ack；唯一索引吸收守卫间隙；M3.10 沉淀为通用幂等框架
3. "消息发出去但消费失败了怎么办？"——重投 9 次→耗尽丢弃+日志→M3.12 对账兜底；Redis 回滚在重试耗尽后做（M3.11），立即回滚会超卖——能讲清"为什么不是立即回滚"是加分项
4. "QPS 提升来自哪？"——同步 74.9 → 异步热身 168（+125%），峰值吸收 847/s；归因=移走同步 DB 事务；剩余瓶颈校验链 SELECT（演进锚点）
5. "为什么 sendSync 而不是异步发送？"——3ms 换确定语义：失败=未成交+可重试；纯异步的响应后补偿属 M3.11

## 6. 下一步

**M3.9 缓存失效广播：Kafka 广播删除各实例本地缓存**——Caffeine 本地缓存的跨实例一致性：更新商户后广播失效消息，各实例消费后逐出本地副本（每实例独立消费组的广播模式）。也是 MQ 框架的第二个用户（广播型消费组首次出现）。
