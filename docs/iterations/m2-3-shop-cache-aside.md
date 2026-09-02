# 迭代任务卡：M2.3 商户缓存 V1 旁路缓存（先更库再删缓存）

| 字段 | 内容 |
|---|---|
| 迭代编号 | M2.3 |
| 分支 | `feature/m2-3-shop-cache-aside` |
| 状态 | 已实现，待用户学习确认（roadmap 勾选留待确认后单独提交） |

---

## 1. 目标

给商户详情读链路接入首个业务缓存：**旁路缓存（cache-aside）**——读时先查 Redis，未命中查库回填；写时**先更新数据库，再删除缓存**。落地 M2.1 RedisCache 门面 + M2.2 Key 治理之后的第一个业务消费方，Key 治理进入"只增枚举项"的常态。

涉及表：`lk_shop`（读/更新/删除路径不变，仅加缓存层）；涉及接口：`GET /api/shop/{id}`、`PUT /api/shop`、`DELETE /api/shop/{id}`（行为不变）；涉及 Redis Key：`lk:shop:info:{id}`（String，JSON 化 ShopVO，TTL 30 分钟调用方显式传入）。

## 2. 设计取舍

- **写路径选"先更库再删缓存"，不选"先删缓存再更库"**：后者在"删缓存后、更库前"的窗口内，并发读会把**旧库值**回填缓存，且更库后无人再删——脏数据要活满整个 TTL；前者最坏情况是"更库后、删缓存前"的短窗口内读到旧缓存，且删缓存动作会把脏数据清掉，窗口只到删除为止。两者都不是强一致（旁路缓存天然最终一致），选脏窗口更短、且能自愈的顺序。备选"更新缓存而非删除"被否：并发写时两个线程交错写缓存可能把旧值写回（写写竞争），删除让下次读统一从库重建，语义更简单
- **删缓存失败不重试不兜底，异常直接上抛**：V1 先暴露问题（铁律 2 演进式开发），失败场景下脏数据由 30 分钟 TTL 自然兜底。后续若要根治可演进延迟双删/订阅 binlog，roadmap 未排期，面试口述即可
- **缓存对象选 ShopVO 而非 Shop 实体**：`detail` 出参就是 VO，命中缓存后零转换直接返回；实体多出的 createTime/updateTime 对读端无用，缓存它们白白占内存
- **DB 未命中不写缓存，直接抛 NOT_FOUND**：恶意/失效 id 会每次穿透到库——这是缓存穿透问题本身，留给 M2.4 空值缓存解决，本迭代刻意不做
- **TTL 30 分钟显式传值，SHOP_INFO 登记为无默认 TTL**：兑现 M2.2 预告——旁路缓存的 TTL 不是 key 的固有属性（M2.5 雪崩抖动时同 key 要传随机 TTL），登记默认值反而误导；调用方一个私有常量 `SHOP_CACHE_TTL` 集中管理
- **key 模板 `shop:info:%s`，不按 m2-2 文档预告字面用 `cache:shop:*`**（用户决策）：与既有 `sms:code:%s` / `user:token:%s` 的"业务域优先"冒号分层风格一致，redis-cli 按域浏览的习惯不破；m2-2 预告处只是示意首个业务 key 落地，非命名规范
- **Redis 故障不降级查库**：cache-starter 设计为 fail-fast 不吞异常（M2.1 决策），V1 让故障显式暴露——降级/熔断策略等真实压测出问题后再演进
- **create / page 不加缓存**：create 后无读缓存诉求（key 尚未产生）；page 是多条件分页查询，key 组合爆炸不适合旁路缓存，且 roadmap 字面范围就是"商户缓存"单条读链路，最小迭代

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `constant/KeyManage.java` | +`SHOP_INFO("shop:info:%s", null, ...)`，首个无默认 TTL 枚举项 |
| `service/impl/ShopServiceImpl.java` | `detail` 读旁路（命中直返 / 未命中查库回填）；`update` 先更库再删缓存；`delete` 先删库再删缓存；注入 RedisCache + KeyBuilder |
| `test/.../constant/KeyManageTest.java` +契约断言 | 锁定 `lk:shop:info:{id}` 完整字符串 + 无默认 TTL（null）契约 |
| `test/.../shop/ShopCacheIntegrationTest.java` 新增 | 4 个场景：回填（含 TTL>0）/ 命中证明 + 更库失效闭环 / 空值不缓存 / 删除失效 |

读链路时序：`GET /api/shop/{id}` → 查 `lk:shop:info:{id}` → 命中反序列化直返；未命中 → 查库 → 不存在抛 NOT_FOUND（不写缓存）→ toVO → `set key TTL=30m` → 返回。

写链路时序：`PUT /api/shop` → 校验存在 → `updateById` → `delete lk:shop:info:{id}`；`DELETE /api/shop/{id}` → 校验存在 → `deleteById` → `delete key`。

## 4. 验证记录（2026-09-02 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 中间件预检 | `docker compose ps` | mysql/redis 双 healthy（Docker Desktop 冷启动后 `docker compose up -d mysql redis` 拉起） |
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| server 测试 | 同上 | **64/64 通过**（原 58 + ShopCache 集成×4 + KeyManage 契约×2；cache-starter 34/34 不变） |
| 新增测试明细 | `.\mvnw.cmd test -pl localink-server -am -Dtest="ShopCacheIntegrationTest,KeyManageTest"` | **9/9 通过**：KeyManageTest×5（含 `lk:shop:info:1` 完整 key + SHOP_INFO 无默认 TTL）；ShopCacheIntegrationTest×4（回填含 0<TTL≤1800 / 直改库仍读旧值→service.update 后缓存删除且读到新值 / 不存在 id 抛 NOT_FOUND 且不写缓存 / 删除后库与缓存双清） |
| 存量回归 | ShopCrudIntegrationTest | 全过——update/delete 链路加了删缓存后原有 CRUD 生命周期断言不受影响 |
| 启动冒烟 | `powershell -File scripts/smoke.ps1` | PING-OK: pong + CLEANUP-OK（进程树已杀、8086 释放） |

**排障记录**：无。

## 5. 学习清单

**核心知识点**
1. 旁路缓存（cache-aside）标准读写时序：读——先查缓存，命中直返（省一次 DB 查询）；未命中查库、回填缓存、再返回。写——先更新数据库，再删除缓存。缓存与库的一致性靠"删"而非"改"来维持，缓存只是库的衍生物，库是唯一事实源
2. 为什么"先更库再删缓存"优于"先删缓存再更库"：后者在删缓存与更库之间的窗口里，并发读会把旧库值回填缓存，且之后无人再删，脏数据活满 TTL；前者最坏只在"更库到删缓存"的短窗口内读到旧值，删除动作会让后续读重建，脏窗口随删除结束。两者都非强一致，旁路缓存本质是最终一致
3. 为什么删缓存而不是更新缓存：并发写下两个"查库→写缓存"交错可能把旧值最后写入（写写竞争）；删除把重建收敛到读路径，一处生成，不存在竞争写坏的问题。代价是删除后的下一读要穿透一次到库
4. 不一致窗口的兜底是 TTL：删缓存失败/进程崩溃在"更库后、删缓存前"时，脏数据最多存活 30 分钟。TTL 是旁路缓存的最后防线，所以业务缓存必须设 TTL（不能裸奔不过期）
5. 缓存 VO 而非实体：缓存的消费场景决定缓存形态——detail 出参是 VO，命中即用零转换；实体中的 createTime/updateTime 对读端无用，不占缓存内存
6. 无默认 TTL 的 key 登记模式：旁路缓存的 TTL 不是 key 固有属性（M2.5 同一 key 要传随机抖动 TTL），登记 null + 调用方私有常量显式传值，语义集中在 Service 一处
7. 穿透问题在本迭代被刻意保留：DB 未命中不写缓存直接抛 NOT_FOUND，无效 id 每次打到库——M2.4 的空值缓存就是补这个洞，"每个问题先存在、再解决"的演进证据链

**面试必问题**
1. "旁路缓存读写流程？为什么写是删缓存不是更新缓存？"——读：缓存命中直返，未命中查库回填；写：先更库再删缓存。删除避免并发写的回填竞争，重建收敛到读路径；若更新缓存，两个并发写交错时旧值可能后写入，脏数据无自愈点
2. "先更新库还是先删缓存？各自的不一致窗口多大？"——先删缓存后更库：并发读可能在窗口内用旧值回填，脏满 TTL；先更库后删缓存：仅"更库到删除"的毫秒级窗口可能读到旧值，且删除失败可由 TTL 兜底自愈，故选后者
3. "删缓存失败了怎么办？"——V1 异常上抛 + 30 分钟 TTL 自然兜底；生产可用延迟双删、订阅 binlog（Canal）异步删、删除失败进重试队列。本迭代选择先暴露问题，压测出真实数据后再演进
4. "缓存和数据库双写不一致有没有彻底解法？"——没有。旁路缓存只能保证最终一致；要强一致得放弃缓存（直读库）或用分布式锁串行化读写（代价大）。工程上用"删 + TTL 兜底"把不一致窗口压到业务可接受范围
5. "缓存什么数据结构？为什么用 String 存 JSON 而不是 Hash 存字段？"——单对象整存整取用 String+JSON：一次 GET 拿全量、序列化简单；Hash 适合需要按字段局部读写的场景（如 USER_TOKEN 会话续期只改一个 field）。本迭代 detail 是整体读取，String 更合适

## 6. 下一步

M2.4 缓存穿透：空值缓存——用不存在的 id 轰炸 `GET /api/shop/{id}` 暴露穿透问题，然后空值短 TTL 兜底，`detail` 的 NOT_FOUND 分支成为改造点。
