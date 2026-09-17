# 迭代任务卡：M3.11 消费可靠性——延迟消息丢弃 + 失败回滚 Redis + 指数退避重试

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.11（B9 批次） |
| 分支 | `feature/m3-11-consumer-reliability` |
| 状态 | 已完成（2026-09-17） |

---

## 1. 目标

兑现 M3.8 埋下的补偿时机学：消费失败不再"重投耗尽后静默丢弃"，而是**耗尽后回滚资格**；积压超龄的建单消息丢弃并退还资格（M3.7 信封 timestamp 首次实战）；回滚自身失败落 `lk_rollback_failure_log`（M5.2 补偿告警的钩子提前埋）。退避策略从默认"立即重试 9 次"升级为指数退避。

## 2. 设计取舍

- **回滚时机 = 重试耗尽后（Recoverer 钩子）**：立即回滚+重投会超卖（资格退回+重投建单），M3.8 已论证。`DefaultErrorHandler` 的 `ConsumerRecordRecoverer` 正是"耗尽钩子"——Recoverer 正常返回即视为已处理、位移提交，不进毒消息循环。指数退避（200ms ×2 封顶 1s，共 4 次尝试）给瞬时故障自愈窗口，总时长 ~1.4s 远小于超龄阈值，重试不会被误判超龄
- **超龄丢弃前必须查 DB（本批最关键的正确性点）**：超龄消息两种身世——从未建成（该回滚）vs 建成了但 ack 丢失被重投（绝不能回滚：用户出集合→可二买→撞唯一索引→CAS 白扣一件）。**幂等标记不可作判据**（M3.10 允许写失败降级），DB 订单行是唯一源真相。超龄是罕见路径，主键查询成本可接受
- **超龄阈值的业务语义**：10 秒内没建成的单视为失败、资格退还可重抢——"用户已等 10s+ 仍看不到订单"的体验取舍（继续建也正确，但体验上等同于失败）。全部参数可配（`localink.seckill.consume.*`），压测放宽不用改代码
- **回滚统一入口 `rollbackSeckillQualification(voucherId, userId, source, detail)`**：三处共用（请求发送失败 REQUEST_SEND / 耗尽 CONSUME_EXHAUSTED / 超龄 STALE_DROP），逆增量 Lua 幂等可重试；执行异常落失败表（source 区分来源，M5.1 扫表补偿）
- **与 roadmap 原文的偏差（主动声明）**：roadmap 写"回滚 Lua：DEL 库存 key 回源"——M3.6 已定案**逆增量**（INCRBY+SREM）：本项目无懒加载回源，DEL 后无人重建等于下次全挂；逆增量幂等可安全重试。database.md 4.12 同步勘误
- **errorHandler 的接线方式（本批最大的坑）**：`@KafkaListener(errorHandler=...)` 属性要的是 `KafkaListenerErrorHandler`（POJO 异常转换器），与容器级 `DefaultErrorHandler` 同名不同物。正确接法：专属 `seckillContainerFactory`（经 Boot 的 configurer 复用 yml 配置——手动 ack 等，再 setCommonErrorHandler）+ `@KafkaListener(containerFactory=...)` 引用

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `config/SeckillConsumeProperties` | 超龄阈值/尝试次数/退避三参，全可配 |
| `config/SeckillErrorHandlerConfig` | DefaultErrorHandler（指数退避+Recoverer）+ seckillContainerFactory |
| `mq/SeckillOrderRecoverer` | 耗尽钩子：解析→查单→回滚/仅日志；无法解析的毒消息直接丢弃 |
| `mq/SeckillOrderConsumer` | containerFactory 接线 + beforeConsume 超龄闸门（查 DB 源真相） |
| `VoucherOrderService` | +rollbackSeckillQualification（Lua+失败落表）、+seckillOrderExists；seckill() 私有回滚换装统一入口 |
| `entity/RollbackFailureLog` + mapper | M1.1 建表首次启用（M5.2 钩子） |
| application.yml | localink.seckill.consume 参数块 |
| 测试 ×4 | 超龄未建单→回滚（Redis 断言）/ 超龄已建单→跳过不动账 / 耗尽回滚（分歧态构造：DB=0+Redis 已扣）/ 失败表 mapper 映射 |

## 4. 验证记录（2026-09-17 本机实测）

全量 **179/179**（175 基线 + 4，BUILD SUCCESS + 用例数双确认）。超龄消息注入方式：KafkaTemplate 直发手工构造的旧时间戳信封 JSON（producer 会打新时间戳，无法注入）。

**排障记录**（两条，均已沉淀）：
1. **errorHandler 属性的类型陷阱**（上文取舍第 6 条）：容器级错误处理器必须走 containerFactory，注解属性是另一族 API——同名异物的经典坑
2. **Write 路径解析事故**：测试文件被写到了工作区根的 `Localink-server`（横线）目录——全盘 find 才定位。工具路径含中文目录时显式校验落点（此后每个 Write 后以编译/ls 验证存在性）

## 5. 学习清单

**核心知识点**
1. **补偿时机学的完整拼图**：请求发送失败（立即回滚）→ 消费失败（退避重试）→ 重试耗尽（回滚）→ 回滚失败（落表等对账）→ 对账（M3.12/M5.1 扫差异）——一条失败走完全程，每一步都有明确的"谁负责、等多久"
2. **Recoverer 语义**：正常返回=已处理（位移提交），抛异常=继续错误流程——它是"重试尽头的那双手"，毒消息（无法解析）也应在此终结而非无限循环
3. **超龄丢弃 = 分级 SLA 思想**：不是所有迟到的工作都值得做——10s 后建成的单对用户而言等于失败；丢弃+退资格把"迟到的正确"换成"及时的失败"。参考项目同款设计（producerTime 超龄丢弃）
4. **判据的可靠性分层**：内存标记（快、可丢）→ Redis 标记（较快、允许写失败降级）→ DB 行（慢、真相）——**做"要不要回滚"这种有代价的决策，必须用最高可靠级别的判据**
5. **指数退避的参数学**：初始 200ms 给瞬时故障喘息、倍增防重试风暴、封顶 1s 保证总时长可控；总退避时长必须 < 超龄阈值（否则重试中消息先超龄了）
6. **逆增量 vs DEL 回滚的选型前提**：取决于体系里有没有"回源重建"——工程方案没有真空最优，只有配套最优（M3.6 结论在消费端场景的再次确认）

**面试必问题**
1. "消息消费失败怎么办？"——退避重试（4 次）→ 耗尽回滚 Redis 资格 → 回滚失败落表等对账；强调"立即回滚+重投=超卖"的时序推演
2. "消息积压了很多旧消息怎么办？"——超龄丢弃+退资格（SLA 思想）；追问"怎么判断该不该回滚"→查 DB 源真相（标记可能丢）
3. "重试次数怎么定的？"——初始/倍增/封顶三参 + 总时长<超龄阈值的约束；可配置是给压测调优留的活口
4. "回滚也失败了怎么办？"——落 lk_rollback_failure_log，M5.1 扫表补偿+告警——兜底链的最后一环是"人 + 对账"
5. "Kafka 重试怎么配的？"——DefaultErrorHandler + ExponentialBackOff + Recoverer；追问 errorHandler 注解属性→能讲同名异物坑（加分项，说明真踩过）

## 6. 下一步

**M3.12 对账日志：Redis 流水（Lua hset）+ DB 对账日志表**——扣减 Lua 内追加流水 Hash 写入（traceId 关联），建单同步写 `lk_voucher_reconcile_log`；为 M5.1 定时比对"Redis 扣了但 DB 无单"铺数据地基（回滚失败表的姊妹钩子）。
