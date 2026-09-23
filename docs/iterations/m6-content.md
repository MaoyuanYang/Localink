# 主题任务卡：M6-A 内容基础（图片上传 + 帖子 + 评论）

| 字段 | 内容 |
|---|---|
| 主题编号 | M6-A（社区差异化开篇） |
| 分支 | `feature/m6-content`（一主题一分支一 PR） |
| 状态 | 已完成（2026-09-23） |

---

## 1. 目标

社区内容三件套（原 M6.1~6.3，F-COM-01/07）：图片上传（可插拔存储，发帖前置）、帖子
（发/删/详情/分页）、评论（两级结构：一级+楼中楼）。lk_post/lk_post_comment 表
（M1.1 建）首次启用，社区线开张。

## 2. 主题内子任务

1. 存储：StorageService 接口 + LocalStorageService（按月分目录/UUID 文件名）+ 静态映射 + 上传端点
2. 帖子：发帖（图≤9/校验）、删帖（本人+级联删评论）、详情（viewed+1）、分页（audit 过滤+倒序）
3. 评论：两级规则（parent 必须是一级）、reply 昵称回填、帖子冗余计数同事务增减、
   一级分页+楼中楼嵌套查询
4. 分页契约：PageVO{total, records} 对齐 web-frontend.md 第 5 节约定（首次落地，后续 W 线直接用）

## 3. 设计取舍

- **存储可插拔 = 接口 + type 选择位**：单实现期直接装配（local），OSS/MinIO 未来加实现换 type——
  接口先立、抽象不超前（不为不存在的第二实现写条件装配）
- **audit_status 先放行（置 1）**：审核状态机属 M6-F（DFA+MQ），之前置 0 会把自己卡死
  （Feed 仅展示 1）——分页/详情的 audit=1 过滤条件先立好，M6-F 接入后改置 0 走异步
- **读路径裸 DB（演进声明）**：UGC 读多写少但当前无压力——若 QPS 上来：详情套商户同款
  旁路缓存（M2 体系）、列表走 ES/Feed（M6-C/D）。不为讲故事预先加缓存
- **两级评论的校验放写入端**：楼中楼 parent 必须是同帖一级评论（两级上限硬约束），
  读取端平铺两级内存组装（单帖评论量级可控）；reply 昵称由 reply_id 反查回填
- **删帖物理删+级联**：教学口径无软删需求；图片文件不清理（孤儿文件声明——删帖清图
  需解析 images 逐个 delete，价值低风险小，接口已备 delete() 留给未来）
- **昵称批量回填**：selectBatchIds 一次查齐拼 Map——避免 N+1；缺失昵称降级"用户+尾号"

## 4. 产出物

| 文件 | 说明 |
|---|---|
| `framework/storage/` | StorageService / LocalStorageService / StorageProperties / StorageWebConfig（静态映射） |
| `controller/UploadController` | POST /api/upload/image（后缀白名单+multipart 5MB） |
| api-model | PostCreateDTO / PostVO / CommentCreateDTO / CommentVO / PageVO |
| entity+mapper | Post / PostComment（表首次启用） |
| service+impl | PostService（detail viewed 本地+1 修复版）/ CommentService（两级规则+计数增减） |
| `controller/PostController` | 帖子四端点 + 评论三端点 |
| application.yml | spring.servlet.multipart + localink.storage |
| 测试 ×7 | 上传（存储+静态访问 200+类型拒）/发帖详情（viewed+昵称+images 拆分+audit 拒）/分页（total/records/倒序/audit 过滤）/删帖级联/两级评论（children+replyNickName+计数）/非法 parent（楼中楼当 parent/不存在）拒/删评论计数回退 |

## 5. 验证记录（2026-09-23 本机实测）

主题 7/7；全 reactor **238/238**（231 基线 + 7），BUILD SUCCESS。

**排障记录**：①YAML 重复顶级键 spring:（multipart 误插独立块）——mapping 冲突上下文起不来；
②detail 返回 viewed 旧值（update 后未同步本地对象）——setSql 自增与内存对象的一致性小坑；
③datetime 秒级精度下分页倒序断言不稳定（同秒连插）——测试手工错开 create_time；
④社区表历史脏数据破坏绝对值断言（失败运行残留）——@BeforeEach 清空社区表（测试独占）。

## 6. 学习清单

**核心知识点**
1. **可插拔存储的边界**：接口+选择位即可，不为第二实现预写条件装配——抽象服务于已知的变点
2. **两级评论的存储与校验**：parent_id/reply_id 双指针（挂哪+回谁）；写入端硬校验两级上限，
   读取端内存组装——结构约束放在数据入口
3. **冗余计数的一致性**：comments 与评论行同事务增减（写入侧维护）；删除时分"删一级连带子"与
   "删楼中楼"两档回退
4. **分页契约先立**：total/records 结构是前后端的共用语言，第一次实现就按约定来（W 线零返工）
5. **先放行后审核的演进路径**：audit 字段与过滤条件先立、值先放行——M6-F 接状态机时只改写入值
   不改查询逻辑

**面试必问题**
1. "评论两级怎么设计的？"——parent/reply 双指针；追问"能不能无限级"→两级上限的读取成本论证
   （组装复杂度/前端呈现），写入端硬校验
2. "帖子图片怎么存的？"——可插拔存储接口+本地实现（月目录/UUID 防猜测），静态映射承接访问；
   追问"换成 OSS 呢"→加实现换 type，业务零改动
3. "评论数怎么维护的？"——同事务冗余计数，删除分两档回退；追问"为什么不实时 count"→
   详情页高频读取，写时维护换读时聚合
4. "帖子要审核吗？"——audit 状态机 M6-F（DFA+MQ），当前放行但过滤条件已立——讲先立条件后接机制的演进

## 7. 下一步

**M6-B 互动关系**：点赞 + 点赞榜 TopN（ZSet，事实源 lk_post_like）+ 关注/取关/共同关注（Set 交集）。
