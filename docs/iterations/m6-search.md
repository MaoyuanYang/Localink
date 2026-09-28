# 主题任务卡：M6-D 搜索（ES 接入 + Kafka 同步 + 检索/高亮/聚合/深分页）

| 字段 | 内容 |
|---|---|
| 主题编号 | M6-D |
| 分支 | `feature/m6-search`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-28） |

---

## 1. 目标

搜索四件套（原 M6.9~6.11，F-COM-04）：ES 环境与 elasticsearch-java client 接入
（localink-search-starter 首批代码）、发帖/删帖 → Kafka → 消费写 ES（复用 MQ 框架）、
搜索接口（一次 _search 三段：query 算分 / highlight 高亮 / aggs 商户聚合）、
search_after 深分页。"DB 事实源 + 派生视图 + 可重建"第五次落地（全量重建兜底）。
ES 是检索原语的提供者，本项目的贡献是 mapping 设计与 DSL 组装，不认领"实现全文检索算法"。

## 2. 主题内子任务

1. ES 环境：compose es 服务加 ik 条件安装（infini 源）、起容器、middleware-setup.md 补验证段
2. search-starter 填实：pom 依赖（BOM 管 8.18.1）+ SearchProperties + SearchAutoConfiguration
   （RestClient/ElasticsearchClient 装配）+ imports 注册 + smoke 测试
3. mapping：索引 `post`（_id=postId），title/content=ik_max_word 索引 + ik_smart 查询，
   nickName/liked/createTime 冗余进文档；入索引前 HTML 转义（高亮 XSS 后端口径）
4. 同步链路：topic `post-search-sync`（自动建）；消息只带 postId+事件类型（UPSERT/DELETE）；
   @TransactionalEventListener(AFTER_COMMIT) 订阅 PostCreatedEvent/PostDeletedEvent →
   sendAsync(key=postId)（同帖同分区 FIFO）；消费端查 DB 组装 upsert（天然幂等）/删文档
5. 搜索接口：GET /api/search/post（keyword multi_match 算分 + shopId post_filter 不算分
   保侧栏全集 + title/content 高亮 + shopId terms 聚合回填店名 + search_after 深分页）
6. 全量重建 rebuildAll：删索引→建 mapping→扫 DB audit=1 全量 upsert——fire-and-forget
   丢失的兜底；不暴露 HTTP（管理接口 W2/W3 演进声明）

## 3. 设计取舍

- **消息只带 postId+事件类型，消费端查 DB 组装**：消息瘦身（全量字段消息=生产消费双端
  schema 耦合，帖字段一改消息版）；消费端查事实源=永远最新态，upsert 天然幂等（重投/
  乱序后到者覆盖）。代价：消费端一次 DB 查询——同步链路不在热路径，可接受
- **同帖分区 key=postId**：同帖的 UPSERT/DELETE 落同分区，Kafka 分区内 FIFO——先发帖后
  删帖的消费乱序（删了又"复活"）不可能发生；跨帖乱序无害（文档互相独立）
- **AFTER_COMMIT 发消息**：事务内发消息=消息先于提交可见，消费端查 DB 查不到新帖
  （UPSERT 查空）或删帖消息先于删行（DELETE 靠 id 无碍但 UPSERT 有）。顺带修正：
  create() 加 @Transactional（M6-C 时单条 insert 无需事务，现在事件订阅者多了，事务
  边界立起来）；FeedServiceImpl.onPostCreated 同步升级 AFTER_COMMIT（回滚不留幽灵收件箱）
- **must 算分 vs post_filter 不算分**：keyword 走 multi_match（title^2,content）参与算分；
  shopId 筛选走 post_filter——不参与算分（过滤条件不影响相关度）且不作用于聚合
  （侧栏商户分面保搜索全集）。备选：shopId 进 bool.filter——算分正确但聚合被过滤，
  侧栏只剩当前商户，违背分面筛选交互
- **search_after 而非 from/size**：深翻页 from+size 是 ES 要遍历+丢弃的分页，默认
  max_result_window=10000 硬顶；search_after 是无状态游标（排序键定位）。score 可重
  （不同文档同分）必须拼 tie-breaker postId——与 Feed 位账防同分漏帖同一课
- **高亮 XSS 后端转义**：title/content 入索引前 HTML 转义，ES 高亮加的 `<em>` 是唯一
  标记——前端任意渲染都安全（React 转义/不转义均无注入面）；备选前端约束
  不用 dangerouslySetInnerHTML——把安全责任放后端一处，不放前端每人
- **nickName 冗余进 ES 文档**：搜索结果高频读，逐 hit 回查 DB 是 N+1；改名不实时
  （下次发帖/rebuild 覆盖）——搜索场景昵称仅展示，接受
- **rebuildAll 简版（删索引重建），不做 alias 切换**：alias 双索引原子切换是零停机
  重建的正解，但演示量级删建窗口秒级；演进声明：索引损坏时 alias 方案补——rebuild
  接口本身已把"可重算"变成一句调用
- **fire-and-forget 同步**：sendAsync 失败仅告警（ES 是可重算派生视图，与 M6-C 推送
  同口径）；备选 sendSync 失败回滚发帖——搜索延迟不值得拖发帖事务
- **3xxxx 错误码仍空缺**：ES 不可用属系统故障走 SYSTEM_ERROR(40000)，社区业务错
  （如 M6-F 审核驳回）才开 3xxxx——错误码分段按业务域不按存储组件
- **ik 装不上降级 standard**：中文按字切分检索质量差但链路完整——教学演示环境网络
  受限时的兜底（任务卡验证记录标注实际采用）

## 4. 接口 / 消息 / ES 索引 / 配置

**接口**（新建 SearchController）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/search/post?keyword=&shopId=&sort=relevance\|time&searchAfter=&size=` | 公开浏览（不强制登录）；SearchVO{records, nextSearchAfter, shopFacets}，null 到底 |

**Kafka**

| 项 | 值 |
|---|---|
| topic | `post-search-sync`（partitions 3，yml 声明自动建） |
| 消息 | `PostSearchMessage(Long postId, PostSyncEvent event)`，UPSERT/DELETE |
| key | String.valueOf(postId)——同帖同分区 FIFO |
| groupId | `localink-server-post-search`（固定组=单写语义） |

**ES 索引 `post`**（_id=postId）

| 字段 | 类型 | 说明 |
|---|---|---|
| title/content | text | analyzer=ik_max_word，search_analyzer=ik_smart；入索引前 HTML 转义 |
| nickName | keyword | 消费端查 DB 冗余（搜索免回查，改名不实时） |
| shopId/userId | long | shopId 供聚合与 post_filter |
| liked | integer | 展示与排序素材 |
| createTime | date | time 排序键 |
| images | keyword[] | index=false 仅存储展示 |

**配置**：`localink.search.uris`（默认 http://localhost:9200）；`localink.mq.topics.post-search-sync`

## 5. 产出物

| 文件 | 说明 |
|---|---|
| docker-compose.yml | es 服务加 ik 条件安装 command（infini 源，restart 自愈重试） |
| `localink-search-starter/` | 首批代码：pom（elasticsearch-java，版本由根 pom 钉 8.15.5）/ SearchProperties / SearchAutoConfiguration（RestClient→Transport→Client 三层装配）/ imports 注册 + smoke 测试 1 例 |
| 根 pom | elasticsearch-client.version=8.15.5 显式管理（钉与服务端一致） |
| api-model | SearchVO / PostSearchVO / ShopFacetVO |
| `search/PostDocument` | ES 文档模型（转义后文本+冗余昵称） |
| `service/PostSearchService` + Impl | ensureIndex（建 mapping+wait yellow）/ indexPost / deletePostFromIndex / search（三段 DSL）/ rebuildAll / refresh |
| `mq/PostSyncEvent` + `PostSearchMessage` | 消息=postId+事件类型 |
| `event/PostDeletedEvent` | 删帖事实事件（M6-F 驳回复用） |
| `mq/PostSearchProducer` | AFTER_COMMIT 订阅双事件 → sendAsync(key=postId) |
| `mq/PostSearchConsumer` | 固定组单写；UPSERT 查库组装 upsert / DELETE 删文档 404 幂等 |
| PostServiceImpl | create 加 @Transactional；delete 发 PostDeletedEvent |
| FeedServiceImpl | onPostCreated 升级 AFTER_COMMIT（M6-C 遗留强化：回滚不留幽灵收件箱） |
| `controller/SearchController` | GET /api/search/post |
| application.yml | localink.search.uris + mq.topics.post-search-sync |
| 测试 ×8 | starter smoke 1 + PostSearchIntegrationTest 7（@SpyBean 上下文 @DirtiesContext 回收） |

## 6. 验证记录（2026-09-28 本机实测）

主题 7/7 + starter 1/1；全 reactor **261/261**（253 基线 + 搜索 7 + starter 1），BUILD SUCCESS；
中间件四容器（MySQL/Redis/Kafka/ES 8.15.5+ik）healthy 下实测；ik 验证：ik_max_word 切
"牛肉面真香值得二刷"=7 词、ik_smart=5 词（不对称分词实测成立）。

**排障实录（六条，教学价值密度最高的一期）**：
①**ES client 版本必须严格对齐服务端**：Boot BOM 默认 8.18.1 连 8.15.5 服务端，cluster.health
响应缺 `unassignedPrimaryShards` 字段（8.16+ 新增）直接解码失败——"同大版本兼容"不可靠，
根 pom 钉 8.15.5
②**建索引后的分片启动窗口**：单节点 create index 后主分片短暂 RECOVERING，期间写入/GET
503 `no_shard_available`——ensureIndex 建 index 后 `wait_for_status=yellow` 阻塞等就绪；
测试 esGet 把 404/5xx/IOException（低层 RestClient 的 ResponseException 是 IOException
子类，不是 ElasticsearchException）一律视为"未就绪"继续轮询
③**MockMvc 中文参数**：手工 URLEncoder 预编码的中文被 URI 模板二次处理成乱码（搜索
关键词变 %E7%81%AB... 字面量，0 命中）——改 `.queryParam()` 由 MockMvc 负责编码；
同一上下文直查 ES 有命中、API 空 results 的对照法定位
④lk_shop 直插缺 NOT NULL 无默认列（images/address/longitude/latitude）——直插兜底补全
⑤**@SpyBean 独立上下文再挤爆 MySQL**（M6-C 同款）：Mockito 定制器改变上下文缓存键，
search 测试类独占一个上下文（+2 分片连接池）→ max_connections=151 超限——@DirtiesContext
(AFTER_CLASS) 跑完即关
⑥compose ik 条件安装（`if [ ! -d plugins/analysis-ik ]`）+ restart 策略自愈网络抖动；
容器重建后插件随容器层消失会重装（数据卷不受影响）

## 7. 学习清单

**核心知识点**
1. **同步双写三罪状 vs 消息驱动**：时效耦合（DB 提交与 ES 可见性）/性能耦合（发帖 RT 拖
   ES）/失败耦合（ES 挂则发帖挂）——Kafka 解耦后发帖只欠一条消息；消息只带 postId+事件
   类型，消费端查事实源组装：永远最新态、upsert 天然幂等（重投/乱序后到覆盖）
2. **同帖分区 key=postId**：Kafka 分区内 FIFO——先 UPSERT 后 DELETE 的乱序（删了又
   "复活"）结构性不可能；跨帖乱序无害（文档互相独立）
3. **AFTER_COMMIT 发消息**：事务内发消息=消费端可能查到旧态/空（消息跑得比提交快）；
   同理顺带修正 Feed 推送监听器——事实先落定，投影后行动
4. **一次 _search 三段**：query（multi_match 算分，title^2 加权）/ highlight（<em> 标记）/
   aggs（shopId terms 分面）；**shopId 走 post_filter**：不参与算分（过滤不该影响相关度）
   且不作用于聚合——侧栏商户分面保搜索全集，这是分面筛选交互的正确语义
5. **ik 不对称分词**：索引 ik_max_word（进粗：切出所有组合，保召回——"牛肉面"同时可被
   "牛肉"搜到）/查询 ik_smart（用细：切最少组合，保精确——减少无关命中）
6. **search_after 深分页 + tie-breaker**：from+size 深翻页要遍历丢弃且 10000 硬顶；
   search_after 无状态游标；score 可重必须拼 postId——与 Feed 位账防同分同一课
7. **派生视图第五次落地**：对账流水/点赞榜/Feed 收件箱/热榜之外——增量消息同步 +
   rebuildAll 全量重算兜底，DB 永远是唯一事实源
8. **版本严格对齐**：跨组件 schema 兼容不能赌"同大版本"——client/服务端、驱动/数据库
   都该钉死同版本

**面试必问题**
1. "ES 数据怎么同步的？"——发帖/删帖 AFTER_COMMIT 发 Kafka（key=postId 同帖 FIFO），
   消费端查 DB 组装 upsert；追问"为什么不全量双写/定时扫表"→双写三罪状、定时扫表延迟
   与扫全表成本；追问"消息丢了怎么办"→fire-and-forget+rebuildAll 兜底（可重算）
2. "重复消息/乱序消息怎么处理？"——upsert 幂等（后到覆盖）；同帖同分区 FIFO；追问
   "DELETE 先于 UPSERT 到呢"——分区 key 保证不可能
3. "搜索接口怎么设计的？"——三段 DSL：算分/高亮/分面；追问"筛选为什么用 post_filter 不用
   filter"→侧栏聚合要全集（分面交互语义），filter 会把聚合一起滤掉
4. "ik 为什么索引和查询用不同分词器？"——召回与精确的不对称；追问"英文呢"→standard 即可，
   ik 是中文场景诉求
5. "深分页？"——search_after+排序键拼 tie-breaker，与 SQL keyset 分页同构；追问"为什么不
   传页码"→页码分页在插入敏感流里会重会漏（同 Feed 滚动分页）
6. "ES 挂了会怎样？"——发帖不受影响（fire-and-forget 告警），搜索报系统错误，恢复后
   rebuild 对齐——故障半径被"派生视图"边界控制住

## 8. 下一步

**M6-E 热点统计**：HyperLogLog 浏览 UV 去重 + 热榜分数模型（点赞/评论/浏览加权 + 时间衰减）
+ 定时快照与热榜接口——liked/viewed 冗余计数在本期进入分数模型。
