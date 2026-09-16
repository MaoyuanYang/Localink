# 迭代任务卡：M3.7 MQ 框架——Kafka 消息模型 + 生产者/消费者模板基类

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.7（B5 批次） |
| 分支 | `feature/m3-7-mq-framework` |
| 状态 | 已完成（2026-09-16） |

---

## 1. 目标

第三个 starter 从空壳启动：Kafka 消息统一封装、生产者门面、消费者模板基类、配置化 topic 注册、生产/消费可靠性配置——为 M3.8 秒杀异步下单（首个业务用户）提供地基，并预留 M3.10 幂等（messageId）、M3.11 重试/回滚/延迟丢弃（beforeConsume/afterConsumeFailure 钩子）的挂点。

## 2. 设计取舍

- **String 序列化 + fastjson2 手动编解码**（与参考项目的 JsonSerializer 不同）：producer/consumer 均为 StringSerializer/Deserializer，框架层用 fastjson2 做 envelope 编解码——对称、无 Spring Kafka 类型头（`__TypeId__`）的跨语言/跨端坑、与 cache-starter 的 JSON 约定统一
- **MessageEnvelope 四元组（messageId/key/headers/timestamp + body）**：messageId 是 M3.10 幂等键、timestamp 是 M3.11 延迟超龄丢弃依据——现在写进去是零成本，将来是消费端判重的接口
- **生产者门面返回 CompletableFuture**：sendAsync 默认回调日志，调用方可 `.exceptionally()` 编排补偿（M3.8 发送失败回滚 Redis 的入口）；sendSync 阻塞到 ISR 落盘确认，给关键消息和测试用，失败抛 MQ_SEND_FAILED(40006)
- **消费者模板"基类不带 @KafkaListener"**：topic/groupId/concurrency 是业务决策，子类自声明监听方法后委托 `dispatch(value, ack)`；基类只钉纪律——解析 → beforeConsume 闸门 → doConsume → 成功才 ack。参考项目同构
- **失败钩子后必须 rethrow**（本迭代最重要的一条纪律）：容器错误处理器的 seek 重投依赖异常触发，**吞掉异常等于确认消费**——不 ack 但也不抛，消息只会躺在分区里等下次重启，既不重投也不死信。闸门跳过则相反：ack 后静默跳过（明确"这条不要了"）
- **topic 配置化注册**（学 bloom filter 的配置驱动）：`localink.mq.topics.<name>.{partitions,replicas}` → KafkaAdmin 显式建表，分区数可见可控；broker auto-create 只是兜底。注意 `List<NewTopic>` 不是 NewTopic 类型 bean、Boot 的 KafkaAdmin 扫描不到，需主动 `createOrModifyTopics`，且 broker 不可达时告警不阻断启动
- **可靠性三件套进 server yml**：acks=all（首领+ISR 全部落盘才算成功）+ enable.idempotence（broker 按 PID+序号去重，重试不产生重复消息）+ max.in.flight=5（幂等开启下保证有序的上限）；消费端 enable-auto-commit=false + ack-mode=manual_immediate（业务成功才提交位移）
- **server 本批只挂依赖与配置**：无 listener 不连线、KafkaAdmin 失败不炸——160 个存量测试零影响的实证

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `MessageEnvelope<T>` | messageId/key/headers/timestamp/body + 静态工厂 |
| `MessageProducer` | sendAsync/sendSync ×[key]；回调日志；sync 失败抛 40006 |
| `AbstractKafkaConsumer<T>` | final dispatch（解析/闸门/业务/ack 纪律 + 失败钩子后 rethrow） |
| `MqProperties` + `MqAutoConfiguration` | localink.mq.topics 配置化建表；@AutoConfiguration(after=KafkaAutoConfiguration) |
| `BaseCode` | +MQ_SEND_FAILED(40006) |
| server pom/yml | +mq-starter 依赖；spring.kafka 可靠性块 |
| 测试 ×4 | 同步往返（body/meta/key 断言）/ 失败重投至成功（3 次尝试 2 次钩子）/ 闸门跳过（不达业务）/ 配置化建表（分区数=2 ≠ broker 默认 3） |

## 4. 验证记录（2026-09-16 本机实测）

- Kafka 容器首启 healthy ~20s（docker compose up -d kafka，KRaft 单节点）
- mq-starter 集成测试 **4/4**（直连 localhost:9092；@DynamicPropertySource 注入随机 topic + `${random.uuid}` 消费组，隔离 Kafka 卷上的历史消息，计数断言可重复运行）
- 全量回归 **164/164**（160 基线 + 4，server 套件挂上 Kafka 依赖零破坏）
- 本批无 HTTP 面变化，不跑 JMeter——QPS 实验由 M3.8 异步化承担

**排障记录**（两条都有普适教学价值）：
1. **@ConditionalOnBean 的顺序陷阱**：`com.localink.mq` 按字母序先于 `org.springframework...KafkaAutoConfiguration` 装配，@ConditionalOnBean(KafkaTemplate) 评估时模板尚未注册 → Bean 静默不建。修复：`@AutoConfiguration(after = KafkaAutoConfiguration.class)`。教训：**条件装配跨自动配置类引用 Bean 时，必须显式排序**——静默跳过比报错更危险
2. **吞异常 = 确认消费**：首版 dispatch 捕获异常后 return（心想"不 ack 就会重投"），实测 20s 无重投——容器只在异常抛出时才走错误处理器 seek；不 ack 不抛的消息只会等重启重平衡。修复：失败钩子后 rethrow。教训：**"不 ack"和"请求重投"是两件事，后者必须抛异常**

## 5. 学习清单

**核心知识点**
1. **acks=all + 幂等生产者**：all 保证 ISR 全落盘才确认（broker 宕机不丢已确认消息）；幂等生产者给每条消息带 PID+序号，broker 发现重复序号即丢弃——"重试"与"恰好一次"的生产端基础。两者合起来才是"发了就一定有、且只有一条"
2. **手动 ack 语义**：enable-auto-commit=false + manual_immediate——位移只在业务成功后提交；消费到一半崩溃，重启后从上次提交处重新消费（at-least-once），幂等消费（M3.10）把 at-least-once 变成 effectively-once
3. **重投的触发条件**：抛异常 → DefaultErrorHandler seek 回分区重读（默认立即重试 9 次）；只不 ack 不抛 → 无重投。这两个机制服务于不同语义：前者"处理失败"，后者"还没处理完"
4. **消息封装的元数据价值**：messageId/timestamp 现在看似多余，M3.10/M3.11 的消费端判重全靠它们——框架接口要为可预见的演进预留零成本字段
5. **KafkaAdmin 与 topic 显式管理**：auto-create 依赖 broker 配置（分区数漂移风险），显式 NewTopic 让分区数进代码库评审
6. **测试的隔离性设计**：共享 Kafka 卷上跑测试，随机 topic + 随机消费组 + earliest——三次运行互不污染，计数断言可重复

**面试必问题**
1. "怎么保证消息不丢？"——生产端 acks=all+幂等；broker 副本（生产可加）；消费端手动 ack、成功才提交——三段各自的责任边界
2. "怎么保证不重？"——生产端幂等去重；消费端 at-least-once 不可避免重复，靠业务幂等（messageId/唯一索引）收敛到 effectively-once（M3.10 展开）
3. "消费失败怎么办？"——抛异常触发重投；重试耗尽进死信（M3.11）；强调"吞异常=确认消费"这个坑
4. "为什么用 String 序列化自己编解码，不用 JsonSerializer？"——对称、无类型头的跨端耦合、与项目 JSON 栈统一
5. "@KafkaListener 为什么不放在基类？"——topic/groupId/concurrency 是业务属性；基类只该钉"纪律"（解析/闸门/ack 时序），不夺走业务的"选择权"

## 6. 下一步

**M3.8 秒杀 V2②：Kafka 异步下单**——框架的首个业务用户：Lua 扣减成功 → sendAsync(seckill-order 消息) → 立即返回；消费者 `SeckillOrderConsumer` 异步建单（CAS+唯一索引），发送/消费失败回滚 Redis（M3.6 补偿 Lua 复用）。exp3 暴露的 74.9 QPS 同步建单瓶颈在此解开，复压对比锚点已就位。
