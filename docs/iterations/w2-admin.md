# 主题任务卡：W2 运营后台（/admin 双区落地）

| 字段 | 内容 |
| --- | --- |
| 主题编号 | W2（Web 前端线第三主题） |
| 分支 | `feature/w2-admin`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-30） |

## 1. 目标

落地 web-frontend.md §3 单工程双区的 `/admin` 运营后台：布局 + 登录守卫 + 商户/券管理 + 订阅提醒 + 订单状态（超时关闭）展示（对应 PRD F-WEB-A01/A02/A03 与 A04 的运营视图部分）。后端配套 3 个 @AdminOnly 补口端点：按活动查订单、手动关单、订阅统计（前置 M5 已就绪；探索确认后端 CRUD 完整但这三块无端点）。

两个范围决策（用户未答复，按推荐口径执行）：**补手动关单端点**（15 分钟延迟队列演示等不了，运营补偿/演示入口定位）；**券管理只管在架券**（不加全量列表端点，已知边界=已下架券在管理页不可见）。

## 2. 主题内子任务

1. **后端 admin 订单查询**：`GET /api/order/admin/page?voucherId=&page=&size=`——按券查订单（分片表键命中、库键缺失广播两库归并，与"我的订单"互为镜像）；复用 VoucherOrderVO + 券名回填。
2. **后端手动关单**：`POST /api/order/admin/{orderId}/close`——直调幂等 closeOrderIfExpired，返回本次是否实际关闭。
3. **后端订阅统计**：`GET /api/seckill-voucher/{voucherId}/subscribe-stats`——SubscribeStatsVO：订阅 ZCARD、状态 Hash 分布（SUBSCRIBED/GRANTED）、notice:sent 标记、活动窗口回显。
4. **前端 admin 骨架**：LoginForm 抽共享组件（C 端/admin 复用）；request.ts 40002 跳转按区（/admin/* → /admin/login）；AdminLayout（侧边菜单+守卫=无 token 跳登录）；AdminLoginPage 与 /admin 子树路由。
5. **前端四管理页**：商户管理（表格+Modal 表单+类型管理）、券与活动（商户下拉+双 Tab：普通券/秒杀活动，金额元输入转分）、订单监控（活动下拉+表格+模拟超时关单按钮）、订阅提醒（统计卡片+机制说明）。

## 3. 设计取舍

- **admin 守卫=登录即可（演示口径）**：admin-guard 默认关闭（@AdminOnly 对已登录用户全放行），后端无 role 字段、前端无从探知管理员身份——不补探针端点（避免范围膨胀）；对外部署开 guard 后非白名单调 admin 接口 40003，由现有"toast 后端 message"路径反馈，前端零改动。守卫只做"未登录不可进"。
- **按 voucherId 查订单与"我的订单"互为镜像 = 分片双口径设计兑现**：pageMyOrders 是 user_id 命中单库/表广播两表；pageOrdersByVoucher 是 voucher_id 命中单表/库广播两库——sharding.md "运营侧按券统计带 voucher_id" 的设计预留，两条路径都只需一条广播 SQL 集合。
- **手动关单端点=运营补偿/演示入口，正路仍是延迟队列**：closeOrderIfExpired 幂等（仅 status=1 可关、回补 DB 库存、Redis 资格回滚+回流发券），页面双轨文案讲清楚"15 分钟自动关 vs 手动补偿"。
- **券管理只管在架券**：现有 list 接口均 status=1；补全量列表需 +2 端点使 PR 膨胀，且"下架后重新上架"是边缘演示场景——已知边界记录：演示时不要下架券（下架即从管理页消失，需 SQL 恢复）。
- **金额元输入提交转分**：W1 排障 2 的语义放反教训产品化——表单 label"支付价（元）/抵扣面值（元）"，提交 Math.round(x*100)，展示侧统一 fenToYuan。
- **预通知全自动，运营页是可观测视图**：创建活动即投递延迟任务（开抢前 lead 分钟）、SETNX 防重、圈名单写收件箱——运营无操作；页面给统计（订阅数/已发券/通知标记）+ 机制说明，并提示已知限制：update 改 beginTime 不重投预通知任务。
- **C 端导航不加后台入口**：/admin 直达 URL（演示口径，双区隔离；生产可在 nginx 层加访问控制）。

## 4. 接口 / 配置

W2 消费/新增接口（消费侧探索已确认契约，此处列新增）：

| 接口 | 方法与路径 | 说明 |
| --- | --- | --- |
| **按活动查订单（新增）** | GET `/api/order/admin/page?voucherId=&page=&size=` | @AdminOnly；Page\<VoucherOrderVO\>；voucherId 必填（40001）；size 钳 50 |
| **手动关单（新增）** | POST `/api/order/admin/{orderId}/close` | @AdminOnly；data=boolean（本次是否实际关闭；已关/不存在 false） |
| **订阅统计（新增）** | GET `/api/seckill-voucher/{voucherId}/subscribe-stats` | @AdminOnly；SubscribeStatsVO |

消费的既有接口：shop CRUD、shop-type CRUD、voucher CRUD、seckill-voucher CRUD、shop/page、voucher/list、seckill-voucher/list、order/page（本人）、subscribe（C 端订阅）。

新增配置：无。

## 5. 产出物

| 文件 | 说明 |
| --- | --- |
| `localink-server/.../controller/OrderController.java`（改） | +adminPage/adminClose 两 @AdminOnly 方法 |
| `localink-server/.../service/VoucherOrderService.java`（+impl，改） | +pageOrdersByVoucher（分片广播+回填） |
| `localink-api-model/.../vo/SubscribeStatsVO.java` | subscribed/granted/noticeSent/title/beginTime/endTime |
| `localink-server/.../controller/SubscribeController.java`（改）+ `SubscribeService`（+impl，改） | +subscribe-stats 端点（ZCARD/Hash 分布/EXISTS） |
| 测试 `AdminOrderPageTest.java` / `SubscribeStatsTest.java` | 造数断言分页/回填/关单幂等/统计计数（默认上下文） |
| `localink-web/src/components/LoginForm.tsx` | 登录表单共享组件（C 端/admin 复用） |
| `localink-web/src/api/{shop,voucher,seckill,order}.ts`（改） | +admin 函数 |
| `localink-web/src/pages/admin/AdminLayout.tsx` 等 6 文件 | 布局+登录+四管理页 |
| `localink-web/src/router/index.tsx`（改）+ `types/api.ts`（改） | /admin 子树 + SubscribeStatsVO |
| `docs/roadmap.md` / `README.md` | W2 勾选、基线更新 |

## 6. 验证记录（2026-09-30 本机实测）

**测试基线 305 → 311/311**（server 205→211：AdminOrderPageTest 3 + SubscribeStatsTest 2 + ShopCrudIntegrationTest +1 images 缺省用例），最终 `mvnw clean test` 全 reactor 串行 BUILD SUCCESS（images 修复后重跑确认）。前端 `tsc && vite build` 通过（29s）。

**GUI 联调冒烟**（jar 8086 + vite 5173）清单全部通过：

1. ✅ 未登录访问 /admin → 守卫跳 /admin/login → 登录（dev 自动取码）→ 跳 /admin/shops，侧边菜单四项 + 商户表格 57 家分页渲染
2. ✅ 商户管理：新建商户（Modal 表单全字段 + 类型 Select 交互）→ API 确认落库（total 58、高新区/¥25）→ GUI 删除（Popconfirm）→ 57 家
3. ✅ 券与活动页：商户下拉 + 双 Tab 渲染；秒杀 Tab 阶段列正确（M4 场次"已结束"、W2 场次"进行中"库存 5、无活动时间的历史券降级"已下架"——W1 的 phaseOf 防御复用生效）；价格列语义正确（支付价 ¥5.00/面值 ¥50.00）
4. ✅ 订单监控：选商户→选活动（下拉联动）→ 订单表格（券名回填/下单时间）→ 点"模拟超时关单" → 状态"已创建"→"超时关闭"+关闭时间出现 + toast"库存回补、资格回滚、订阅者自动回流发券"
5. ✅ 订阅提醒：另一账号订阅后统计页显示（订阅排队 1/排队中 1/已发券 0/预通知"否"——活动 -30min 开始故未投预通知，符合 delay<0 不投设计；活动窗口回显）
6. ✅ admin 区 40002 分区跳转：塞垃圾 token 触发 stats 请求 → 清 store → 跳 /admin/login（非 C 端 /login）

**冒烟数据已清扫**：W2 场次券两表、已关订单（4 物理表 by id）、两个冒烟用户、7 个 Redis key 删除复查归零。

**排障记录**：

1. **新建商户 500：lk_shop.images 列 NOT NULL 无默认值**——ShopDTO.images 可选但 INSERT 缺列即 SQL 异常，后端 create 链路既有缺陷（首次被 W2 admin 建商户路径暴露；W0/W1 未走此分支）。修复：Service create 时 images null→空串 + ShopCrudIntegrationTest 补"images 缺省可建"用例。
2. **普通券 Tab 混入秒杀券**：`GET /api/voucher/list` 不分 type（仅 status=1），W0 在 ShopDetailPage 过滤过、AdminVouchersPage 初版漏滤——联调实拍发现秒杀场次出现在普通券 Tab。修复：NormalVoucherTab 加 `type===1` 过滤（与 W0 同口径）。
3. **Docker Desktop 中途停止致全量首跑失败**：ES smoke 测试连不上（Communications link failure 实为全部容器停止）；重启 Docker 后容器未随 daemon 自启（历史 Exited 状态）需手动 `docker compose up -d`，且 ES 需 `docker compose --profile es up -d`（profile 隔离）。
4. **运行中 jar 锁文件致 repackage 失败**：Windows 下 java 进程持有 jar 句柄，spring-boot repackage 无法 rename——停后端再打包（顺序依赖教训）。
5. **IAB 浏览器 webview 短暂不可用**：会话中途 tabs.new 报 "browser guest not attached"；等待后自愈。期间 playwright click 超时但 fill/evaluate 正常——AntD 交互改用 evaluate 直点按钮/DOM 模拟 Select（mousedown→option click）完成冒烟。
6. **admin GET 端点的登录盲区（设计内发现）**：LoginInterceptor 只拦非 GET，@AdminOnly 在 guard 关闭时全放行——adminPage/subscribe-stats 若不判 UserHolder 则匿名可读；Service 显式判登录补齐口径（"admin 接口至少登录"），测试覆盖 40002。

## 7. 学习清单

**核心知识点**

1. **单工程双区**：/ 与 /admin 共享 api/types/utils/axios 封装，仅路由前缀分区——对比双工程（两套构建/两份封装）的维护成本；40002 会话失效按区跳转是双区唯一的全局差异点。
2. **分片查询双口径**：按 user_id 查（C 端"我的订单"）命中库键单库+表广播；按 voucher_id 查（运营"按活动看订单"）命中表键单表+库广播——分片设计时预留两个入口键，两种查询都只花广播代价；跨键查询（既无 user_id 又无 voucher_id）才会全库全表 fan-out。
3. **幂等关单的状态机收敛**：closeOrderIfExpired 条件 UPDATE（仅 status=1）天然幂等——重复调用/延迟消息重复投递都安全；关单不是孤立动作：DB 库存回补 + Redis 资格回滚 + 订阅回流发券在同一闭环。
4. **运营可观测性 vs 可操作性**：预通知链路全自动（创建即挂钟/SETNX 防重/圈名单），运营页只做观测（统计+标记）不做操作——好的自动化让运营界面变薄。
5. **前端守卫的边界**：路由守卫解决"未登录不可见"，不解决"谁能管理"——权限真相在后端（admin-guard 白名单）；演示口径关闭 guard 是明确的风险决策（deploy.md 已注明对外部署必须开）。

**面试必问题**

1. "运营后台怎么查分片订单表？"——按活动查：voucher_id 是表分片键，WHERE voucher_id=? 路由到两库各一张物理表（库广播）；与 C 端按 user_id（库命中+表广播）互为镜像；ShardingSphere 自动归并分页 count。追问"全量订单扫描呢"→两键皆失全 fan-out，运营全量报表应走别库/别表（或 ES/数仓），不在交易库上做。
2. "手动关单和延迟队列关单会不会冲突？"——不会：两者都收敛到同一条件 UPDATE（WHERE status=1），先到者生效、后到者幂等跳过；库存回补/资格回滚也各有一致性保障（逆增量 Lua 幂等、失败落失败表对账重试）。
3. "为什么前端不做权限？"——后端无角色模型，admin 判定是手机号白名单（admin-guard）；前端守卫只挡未登录，权限执行完全在后端拦截器——前端权限只是体验，后端权限才是边界。

## 8. 下一步

W3 社区 + 后台扩展：Feed/发帖（图片上传）/评论/点赞/关注/搜索/热榜/签到 + 审核队列 + Top 买家/对账看板（前置 M6 已就绪）。
