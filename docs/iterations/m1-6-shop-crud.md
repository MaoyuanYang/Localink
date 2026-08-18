# 迭代任务卡：M1.6 商户类型 + 商户 CRUD

| 字段 | 内容 |
|---|---|
| 迭代编号 | M1.6 |
| 分支 | `feature/m1-6-shop-crud` |
| 状态 | 已完成（PR #20 合入，2026-08-17 用户学习确认，roadmap 已勾选） |

---

## 1. 目标

商户域业务正式开始：商户类型完整 CRUD + 商户 CRUD（详情/分页/增删改），M1.2 配置的分页插件首次实战；同时把 M1.5 的登录拦截器升级为**读写分离约定**——GET 公开、写操作需登录，一套机制覆盖到 M6。PRD F-SHOP-01 落地。

涉及表：`lk_shop_type`、`lk_shop`；涉及接口：9 个（见产出物）；涉及 Redis Key：无。

## 2. 设计取舍

- **鉴权升级为读写分离约定**：LoginInterceptor 按 HTTP 方法裁决——UserHolder 有用户放行；否则 `/api/user/**` 一律 40002（login 已路径排除），非 GET 一律 40002，GET 放行。排除名单 `/api/user/login` + `/api/sms/**`（登录前置接口）。**不采用 /api/admin 前缀方案**：M6 的 C 端写操作（发帖/评论/点赞）不在 admin 语义下，前缀方案会导致双机制并存；读写分离一条规则管到底，排除名单稳定不膨胀
- **单 DTO 创建/更新两用（id 语义区分）**：id 空=创建（服务端生成）、非空=更新；更新缺 id 在 service 层报 40001。代价：id 必填校验不在注解层；收益：DTO 数量减半，接口契约直观
- **类型删除加占用守卫**：删类型前 count 该类型下商户，>0 抛 `SHOP_TYPE_IN_USE(50001)`——**商户域 5xxxx 错误码开篇**，同步补 architecture.md §5 分段（原文档无商户段，属补漏）。这是逻辑外键约定下唯一必要的跨表校验（防孤儿商户）；商户创建不反向校验 typeId 存在性，按需演进
- **sold/comments/score 不进 DTO**：展示计数由种子数据与后续业务事件维护，管理接口不暴露写入口，防手工污染
- **物理删除**：PRD 无软删需求；与 database.md 一致，不预建 deleted 字段
- **分页直返 MP `Page<ShopVO>`**：records/total/size/current 与 web-frontend.md 契约（page/size 请求 + total/records 响应）天然对齐，不自造分页包装；Entity→VO 用 Spring BeanUtils（不引新依赖）
- **创建接口返回 id 字符串**：前端跳转详情需要；雪花 ID 精度处理与 M1.4/M1.5 一致（VO 的 id/typeId 均 ToStringSerializer）

## 3. 产出物

| 文件 | 说明 |
|---|---|
| 接口 ×9 | 类型：GET /api/shop-type/list（公开）、POST/PUT/DELETE（登录）；商户：GET /api/shop/{id}、GET /api/shop/page（公开），POST/PUT/DELETE（登录） |
| `ShopTypeDTO`/`ShopDTO`（api-model） | 校验：name/address @NotBlank、typeId/经纬度 @NotNull |
| `ShopTypeVO`/`ShopVO`（api-model，vo 包首次启用） | id/typeId ToStringSerializer |
| `ShopType`/`Shop` 实体 + 两个 BaseMapper | ASSIGN_ID 雪花主键 |
| `ShopTypeService`/`ShopService` + 实现 | 列表排序、分页过滤、存在性校验、占用守卫 |
| `ShopTypeController`/`ShopController` | @Validated @RequestBody |
| `LoginInterceptor` + `AuthWebConfig` | 读写分离逻辑 + `/api/**` 路径 + 排除名单 |
| `BaseCode` + `architecture.md` | 50001 + 错误码分段补 5xxxx 商户 |
| 父 `pom.xml` | maven-compiler-plugin + `<parameters>true</parameters>` |
| 测试 ×10 | 类型 CRUD×4（含占用拒删/缺 id）、商户 CRUD×2（全生命周期/分页过滤）、鉴权×4（写拒读通/带 token 创建/缺字段 40001） |

## 4. 验证记录（2026-08-17 本机实测）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 全量构建 | `.\mvnw.cmd clean package` | BUILD SUCCESS，13 模块 |
| 测试 | 同上 | **31/31 通过**（+10 商户域） |
| 冒烟（脚本化） | `powershell -File smoke-m16.ps1` | 登录 32 位 token → 建类型/建商户（返回 id 字符串）→ **无 token 读详情/分页正常（公开）**→ 更新生效 → **无 token 写被拒 40002** → 删除后详情 40004 → CLEANUP-OK 无残留 |

**排障记录**（本迭代踩出两个有教学价值的坑）：
1. **`@RequestParam` 参数名丢失，GET /api/shop/page 报 500**：`IllegalArgumentException: Name for argument of type [java.lang.Long] not specified`。根因：Spring Framework 6.1 起不再通过字节码推断参数名，`@RequestParam` 不写 name 时依赖编译期 `-parameters` 保留形参名；spring-boot-starter-parent 默认带此配置，而本项目是自定义 parent，漏掉了 → 父 pom 的 maven-compiler-plugin 加 `<parameters>true</parameters>`（@PathVariable 同理受益）
2. **创建商户 500：`Field 'images' doesn't have a default value`**：lk_shop.images 为 NOT NULL 无默认值（database.md 既定），测试请求体漏传 images。修测试体补字段，schema 不动——创建商户必传图片是合理契约
3. 冒烟脚本两处编码坑（沿用 M1.4 经验）：PS 5.1 读无 BOM 的 UTF-8 脚本按 GBK 解析，中文字符串直接破坏语法 → **脚本一律纯 ASCII**；curl 响应含中文经 GBK 控制台管道时 ConvertFrom-Json 解析失败 → 束装层问题，应用响应本身正确（40004 原文可见）

## 5. 学习清单

**核心知识点**
1. Spring 6.1 参数名机制变更：反射形参名依赖 `-parameters` 编译标志，历史项目升级 Spring Boot 3.2+ 的高发故障点
2. MP 分页机制：PaginationInnerInterceptor 拦截 Page 参数 → 改写 SQL（count + limit）；Entity 页→VO 页的转换姿势（新建 Page 搬 total/size/current + records 映射）
3. DTO/VO/Entity 三层分工：DTO 入参契约（带校验）、VO 出参契约（带序列化策略）、Entity 持久化映射——BeanUtils 拷贝的边界（忽略 id 防创建时被覆盖）
4. 鉴权模型的两种流派：路径前缀（admin 区）vs 读写分离（方法裁决）——各自适用场景与扩展成本
5. MySQL 严格模式：NOT NULL 无默认值列插入 null 直接报错（sql_mode 含 STRICT_TRANS_TABLES），契约前置到建表

**面试必问题**
1. "公开接口和登录接口怎么区分？"——拦截器按 HTTP 方法裁决：GET 公开、写操作需登录，/api/user/** 全保护；排除名单只含登录前置接口（login/sms），规则可扩展不膨胀
2. "分页怎么做的？"——MP 分页拦截器改写 SQL；响应直返 Page（records/total），与前端契约对齐；追问深分页问题（limit 大偏移的性能与游标方案）
3. "为什么分 DTO/VO？"——入参校验与出参序列化策略不同（VO 的 id 转字符串防精度丢失），Entity 不直接暴露（字段泄漏 + 持久化细节外溢）
4. "删除商户类型要检查什么？"——逻辑外键无 DB 约束兜底，应用层 count 守卫防孤儿数据；这是"不建物理外键"约定必须配套的纪律

## 6. 下一步

M1.7 普通优惠券 CRUD：lk_voucher 实体落地，券与商户关联查询（商户详情页券列表），普通券领取接口预留（W0 前端需要）。
