# 主题任务卡：M6-F 风控与特色（DFA 两级审核 + BitMap 签到 + GEO 附近商户）

| 字段 | 内容 |
|---|---|
| 主题编号 | M6-F（M6 收官主题） |
| 分支 | `feature/m6-audit-sign-geo`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-28） |

---

## 1. 目标

四件套（F-COM-06 + F-ACC-04 + F-SHOP-03）：DFA 敏感词同步初筛、MQ 异步审核状态机
（驳回同步删 ES 文档）、BitMap 用户签到与连续签到统计、GEO 附近商户检索。cache-starter
的 BitMap/GEO 分组落地后，m2-1 预留的全部扩组点兑现完毕；首个 3xxxx 社区错误码开段。

## 2. 主题内子任务

1. starter 扩 `RedisBitMapOps`（setBit/getBit/bitCount/bitFieldGetUnsigned）与
   `RedisGeoOps`（add/remove/search 按距离升序带距离）两组
2. DFA 引擎：HashMap 嵌套 Trie（end 标记+跳干扰字符），双词库
   （显性 `dict/sensitive-words.txt` / 隐性 `dict/audit-risk-words.txt`，ClassPathResource
   装配仿 lua 先例）
3. 两级审核：同步初筛命中→拒发帖（POST_AUDIT_REJECTED 30001，帖不落库）；未命中→
   audit=1 先放行 + topic `post-audit` 异步复审；复审命中隐性词→audit=2 +
   复用 PostDeletedEvent 删 ES（M6-D 链路零新增）
4. 评论走同步初筛（命中拒发），异步状态机演进声明
5. BitMap 签到：`user:sign:{userId}:{yyyyMM}`（今天在最低位），连续签到=位串从最低位
   数连续 1（顶满跨月续查上月），SETBIT 幂等
6. GEO：启动全量灌入（坐标非 0）+ 商户 CRUD 三挂点维护 + nearby 按距离升序检索

## 3. 设计取舍

- **两级审核=信任分级（先审后发档 + 先发后审档并存）**：同步层挡确定性已知风险（显性
  词库命中直接拒——拒之门外成本为零）；异步层挡不确定性风险（隐性词库先发后审，命中
  收回）。与 M6-A 预告"改置 0 走异步"的差异：置 0=所有帖卡到消费完成才可见（Feed/搜索/
  热榜全断链）；先放行 1+驳回收回，正常内容零延迟——"先发后审 vs 先审后发按信任分级"
  的完整落地
- **驳回复用 PostDeletedEvent**：审核驳回在语义上就是"内容撤下"——与删帖共享同一条
  ES DELETE 同步链路（M6-D 设计时的预留兑现），链路零新增；Feed/热榜/点赞/搜索的
  audit=1 读端过滤自 M6-A 起已全线立好，驳回即刻生效
- **DFA 手写 HashMap Trie，不引 Hutool WordTree**：教学口径要"Trie=DFA 的 HashMap 嵌套
  实现"可见可控；复杂度 O(文本长度×最大词长) 与词库大小无关——"为什么不用正则"：
  正则内部本是自动机，但 10 万词编进一个正则的分支不可控、编译回溯风险高
- **end 标记防前缀漏判**：词库有"赌博网站"，文本只含"赌博"不算命中（词的边界由 end
  标记决定，前缀到达不算词）；**跳干扰字符**：扫描时跳过空格/*等干扰符——"赌 博"
  照样命中（绕过成本高于识别成本的做法没有意义）
- **词库=classpath 文件，双库两实例**：显性库（拒发）与隐性库（收回）分离=信任分级的
  物理化；演进：DB 词库+管理端热更新（W2 审核队列页一起做）
- **评论只走同步初筛**：评论驳回直接拒发（与帖同级拦截），异步状态机不做——评论无
  独立 ES 索引与 Feed 推送，驳回收回的链路收益低；演进声明待评论量级上来
- **签到"今天在最低位"**：offset=dayOfMonth-1，BITFIELD 取"本月 1 日至今"位串后从
  最低位向高位数连续 1——今天永远是最低位，跨天无需迁移；位串顶满（本月天天签）时
  续查上月 key 完整跨月连续
- **GEO 底层口径**：Redis GEO=ZSet + geohash 52 位整数 score（经纬度编码进 score），
  GEOSEARCH 按编码区间圈候选再算精确距离——不是"为每个点两两算距离"
- **GEO 启动全量灌入 + CRUD 增量维护**：仿布隆初始化器先例；坐标 0/0 的历史脏行不灌
  （null 坐标 GEOADD 无意义）；商户 update 覆盖写 GEOADD（member 已存在则更新坐标）
- **nearby 不分页用 count 截断**：距离排序天然截断（ASC COUNT N 是 GEOSEARCH 原生
  语义）；深翻页场景演进声明

## 4. 接口 / Redis Key / 消息

**接口**（4 个新端点）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/user/sign` | 签到（幂等）；返回 SignVO |
| GET | `/api/user/sign` | 签到状态：signedToday/continuousDays/monthDays |
| GET | `/api/shop/nearby?longitude=&latitude=&radius=5000&count=10` | 附近商户按距离升序（公开浏览） |
| （内部） | 发帖/评论带显性词 | 30001 拒绝；异步复审驳回 → 帖 audit=2 不可见+ES 删除 |

**Redis Key**（KeyManage 登记）

| Key | 模板 | 结构 | TTL | 恢复策略 |
|---|---|---|---|---|
| USER_SIGN | `user:sign:%s:%s`（userId, yyyyMM） | BitMap（第 dayOfMonth-1 位=当天） | 62 天 | 丢失=签到记录丢失，接受（database.md §7） |
| SHOP_GEO | `geo:shop` | GEO（member=shopId，geohash score） | 常驻 | 由 lk_shop 全量灌入重算 |

**Kafka**：topic `post-audit`（partitions 3），消息 `PostAuditMessage(Long postId)`，
key=postId；消费组 `localink-server-post-audit`（固定组）

**BaseCode**：`POST_AUDIT_REJECTED(30001, "内容包含敏感词，已被驳回")`——3xxxx 社区段首条

## 5. 产出物

| 文件 | 说明 |
|---|---|
| cache-starter `RedisBitMapOps` | setBit/getBit/bitCount（connection 回调，ValueOperations 无此门面）/getUnsigned（**位序翻正**） |
| cache-starter `RedisGeoOps` | add/remove/search（千米查询换算米，Metrics 无 METERS 枚举） |
| `framework/dfa/SensitiveWordDFA` | HashMap 嵌套 Trie（end 标记+跳干扰字符），双词库两实例（DfaConfig 装配） |
| `dict/` 双词库 | sensitive-words.txt（显性，拒发）/audit-risk-words.txt（隐性，收回） |
| BaseCode | POST_AUDIT_REJECTED(30001)——3xxxx 社区段首条 |
| mq `post-audit` | PostAuditMessage + PostAuditConsumer（固定组，rejectIfRisky 幂等） |
| PostServiceImpl | create 初筛（命中拒发）+ 提交后投复审消息 |
| CommentServiceImpl | 评论同级初筛 |
| `service/PostAuditService` | rejectIfRisky：@Transactional 内置 audit=2 + 发 PostDeletedEvent（消费端无事务，须自带事务才能触发 AFTER_COMMIT 监听器删 ES） |
| `service/SignService` + Impl | checkIn/status；连续签到统一"从最高位（今天）向下数"，跨月顶满续查 |
| `framework/shop/ShopGeoInitializer` | 启动全量灌入（坐标非 0，幂等覆盖） |
| ShopServiceImpl | create/update GEOADD 覆盖写、delete GEO remove、nearby（批量回填带距离） |
| Controller | POST+GET `/api/user/sign`；GET `/api/shop/nearby` |
| 测试 ×18 | starter BitMap/GEO 2 + DFA 单测 4 + 审核 4 + 签到 4 + GEO 4 |

## 6. 验证记录（2026-09-28 本机实测）

主题 16/16（DFA 4+审核 4+签到 4+GEO 4）+ starter 2/2；全 reactor **287/287**
（269 基线 + 18），BUILD SUCCESS；四容器（含 ES）healthy 下实测。
首跑 ShopCacheIntegrationTest 空标记 TTL 窗口断言偶发失败一次（隔离跑与全量复跑均绿，
负载下时序偶发，未改代码）。

**排障实录（六条，含一个 Redis 本身的坑）**：
①**BITFIELD 与 SETSET 位序相反**（实测 Redis 7.4）：SETBIT 的第 k 位落在 BITFIELD
u{W} 返回值的第 W-1-k 位——starter `getUnsigned` 内翻正为 SETBIT 位序，并以**非回文
位串断言**锁死（初版测试用 101 回文串侥幸通过——回文会掩盖位序 bug，测试数据要避开
对称形态）
②**Spring Data Redis 3.5 API 差异连环**：ValueOperations 无 bitCount（走 connection
回调）、Metrics 无 METERS 枚举（千米查询×1000 换算）、GeoReference 泛型必须
M=String——javap 本地 jar 查真签名比猜文档快
③**连续签到计数方向**：跨月续查上月要从月末（高位）向下数——streak 衔接的是上月末
尾不是月初；位序翻正后当前月与历史月统一为"从最高位向下"，两分支合一
④**异步驳回与"创建后仍可见"的竞速**：复审消费是秒级热链路，中间态断言天然 flaky
（同用例首跑绿、次跑红）——先放行语义由干净帖用例覆盖，风险帖用例直取终态
⑤**测试残留互踩**：GEO 失败运行的 forEach 清理中途抛出阻断后续删除→残留坐标盒脏行
污染下一轮断言——清理改逐店 try-catch + @BeforeEach 坐标盒清扫；直插 mapper 绕过
service 的数据不会清 GEO——测试尽量走 service 全链路
⑥UserController 构造器加参影响测试的 standaloneSetup 单参 new——@RequiredArgsConstructor
构造面变更要全局搜直接构造点

## 7. 学习清单

**核心知识点**
1. **两级审核=信任分级**：同步初筛挡确定性已知风险（显性词，先审后发，拒之门外成本
   零）；异步复审挡不确定性风险（隐性词，先发后审，命中收回）——两档并存而非二选一
2. **Trie=DFA 的 HashMap 嵌套实现**：O(文本×最大词长) 与词库大小无关；end 标记定词
   边界（前缀到达不成词）；跳干扰字符让绕过成本高于识别成本；vs 正则=正则内部本是
   自动机但海量词分支不可控
3. **驳回复用删帖链路**：驳回语义=内容撤下，与删帖共享 PostDeletedEvent→ES DELETE
   （M6-D 预留兑现）；AFTER_COMMIT 边界：消费端线程无事务，驳回方法须自带
   @Transactional 才能触发事件监听器
4. **BitMap 签到的账**：一人一月 31 位=4 字节；SETBIT 幂等；BITFIELD 位序坑（见排障①）；
   连续签到=从今天向回数连续 1，跨月顶满续查
5. **GEO 底层**：ZSet + geohash 52 位整数 score——按编码区间圈候选再算精确距离，不是
   两两算距离；GEOADD 覆盖写=坐标更新的幂等维护
6. **首个 3xxxx 开段**：错误码按业务域（社区）不按存储组件（Redis/ES）分段

**面试必问题**
1. "敏感词过滤怎么做的？"——Trie/DFA、复杂度证明、end 标记、跳干扰、vs 正则；追问
   "词库 10 万条呢"——复杂度不变，内存=词总字符数×节点开销，可换双数组 Trie 压缩
2. "先发后审还是先审后发？"——信任分级两档并存；追问"驳回后搜索/Feed 怎么同步"——
   驳回=撤下，复用删帖事件：ES 删文档 + 读端 audit 过滤即刻生效
3. "签到为什么用 BitMap？连续签到怎么算？"——4 字节/人/月 vs Set 的内存账；位串从
   今天向回数连续 1、跨月续查；追问"BITFIELD 有什么坑"——位序与 SETBIT 相反（亲测，
   已在封装层翻正）
4. "附近商户怎么实现？"——GEOADD/GEOSEARCH、geohash 区间圈选+精确距离过滤；追问
   "为什么不在 DB 算"——全表两两 Haversine vs 内存索引圈选
5. "词库怎么热更新？"——当前 classpath 文件双库两实例；演进 DB 词库+原子换引用重建

## 8. 下一步

**M7-A 全链路压测与调优**：JMeter 脚本与基线报告（拒绝路径/缓存命中场景，把简历数字
跑成真话）——M6 六主题全部收官后的验收大考。
