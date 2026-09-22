# 迭代任务卡：M3.14 限流框架②——RateLimitHandler 场景化组装

| 字段 | 内容 |
|---|---|
| 迭代编号 | M3.14（B11 批次） |
| 分支 | `feature/m3-14-rate-limit-handler`（堆叠于 m3-13，审阅顺序 m3-11 → 12 → 13 → 14） |
| 状态 | 已完成（2026-09-22） |

---

## 1. 目标

把 M3.13 的两个 Lua 原语组装成业务可用的限流能力：`@RateLimit(scene, dimensions)` 注解 + 场景配置驱动
（算法与参数全在 yml，调参不发版）、IP/用户双维度独立计数、静态+动态白名单与封禁、Redis 故障 fail-open。
首个生产场景接入短信验证码发送（IP 维度 1 次/60s 防刷）。

## 2. 设计取舍

- **注解声明"在哪限"，配置声明"怎么限"**：注解只带 scene+dimensions，算法/阈值/窗口全在
  `localink.ratelimit.scenes.{scene}`——限流参数是运营态数据（压测调优、活动放量），发版才能改的限流是摆设
- **双维度 + 维度级参数覆盖（本批最有价值的现场发现）**：初版两维度共享场景参数，测试立刻暴露——
  共享的 IP 桶会比单个用户先耗尽（所有用户挤一个 IP 桶），"用户独立额度"名存实亡。生产口径通常是
  "IP 宽、用户严"（如 IP 100/min、用户 5/min），故 SceneRule 增加 `overrides: {USER: {...}}` 字段级覆盖合并。
  对齐 hmdp 系标准面试题"限流维度怎么选"的真实工程答案：不是选维度，是每维度独立配额
- **fail-open 的结构正确性（本批最险的坑）**：try 只包"判定"，`proceed()` 在 catch 外单次调用。若把
  fail-open 写成 `catch (Exception e) { return pjp.proceed(); }` 且 try 内含 proceed——业务方法自身的异常会被
  误判为限流器故障并**二次执行**（幂等灾难，重复建单/重复扣款级别）。专门用例锁定：业务异常原样透传 + 计数恰 1
- **拒绝抛 LocalinkException(RATE_LIMITED)（单参构造）**：对外 message 用枚举统一口径，scene/dimension
  细节进日志（对内观测、对外不泄露限流拓扑）；复用全局异常处理器零新增出参逻辑
- **用户维度经 SPI（RateLimitUserResolver）**：starter 不能依赖 server（依赖规则），用户身份由宿主解析——
  server 侧 UserHolderRateLimitUserResolver 桥接 UserHolder；匿名返回 null 跳过该维度（IP 兜底）。
  resolver 缺失时降级仅 IP + 一次性 warn
- **白名单优先级最高、封禁次之**：白名单（静态 yml + Redis 动态 Set 并集）跳过一切限流；封禁直接拒绝。
  动态名单经 RateLimitAdmin 门面（SADD/SREM 即时生效），运维不碰 redis-cli
- **IP 提取：X-Forwarded-For 首段 → X-Real-IP → remoteAddr**：非请求线程返回占位符（AOP 可挂非 Web 方法）。
  XFF 可伪造——生产应在网关层覆盖该头，应用只信任入口注入版本（注释已声明）
- **切面 @Order(-200)**：限流是最外层守卫，先于幂等（-100）与事务——被限流拒绝的请求连幂等标记都不该写

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `RateLimit` 注解 + `Dimension`/`Algorithm` 枚举 | scene+dimensions 声明式接入 |
| `config/RateLimitProperties` | scenes 规则表 + `overrides` 维度覆盖（字段级合并 effective()）+ 静态白名单 |
| `aspect/RateLimitAspect` | 白名单→封禁→双维度判定；fail-open 结构；@Order(-200) |
| `user/RateLimitUserResolver` | 用户维度 SPI |
| `web/RateLimitIpExtractor` | XFF→X-Real-IP→remoteAddr |
| `RateLimitAdmin` | 动态白名单/封禁门面（Redis Set） |
| `BaseCode`（common） | +RATE_LIMITED(40008) |
| server：pom 依赖声明 | ratelimit-starter 接入（M3.10 漏声明教训的前置补上） |
| server：`UserHolderRateLimitUserResolver` | UserHolder 桥接；匿名 null |
| server：SmsController @RateLimit + yml 场景 | sms-send：滑动窗口 1 次/60s，IP 维度（未登录接口） |
| pom（starter） | +aop、+spring-web、+servlet-api(provided)——RequestContextHolder 取请求上下文 |
| 测试 ×7 新（starter）+ ×2 新（server） | 见验证记录 |

## 4. 验证记录（2026-09-22 本机实测）

全 reactor **199/199**（190 基线 + 9；分模块 cache 48 / lock 9 / idempotent 6 / ratelimit 16 / mq 4 / server 116），BUILD SUCCESS。

**starter 切面 7 例**：窗口场景第 3 次拒绝且业务计数恰 2（被拒不执行）/ 不同 IP 独立计数 / 静态白名单 5 连发全过 /
动态白名单即时生效 + 解白后封禁即拒（message="访问受限"）/ **用户维度 override=1 同用户第 2 次拒、换用户独立额度
（共享 IP 桶 threshold=100 不先耗尽）** / 令牌桶容量耗尽拒绝 / **业务异常透传原码（PARAM_ERROR）且只执行一次**。

**server 短信场景 2 例**（MockMvc 全链路）：首 发 ok + 二连发 40008（枚举 message）/ 动态白名单 IP 三连发全过
（每次新手机号，隔离 SmsService 自身 60s 重发限制的干扰）。

**排障记录**：①CGLIB 代理字段陷阱——@Autowired 注入的是代理对象，实例计数器字段读到 null；改 static 计数
（代理只转发方法调用，静态字段类级共享）。②维度共享参数缺陷由测试现场暴露（取舍第 2 条）。
③单模块构建不 -am 解析不到兄弟构件（本地仓库无 install），surefire 旧报告误导排查方向。

## 5. 学习清单

**核心知识点**
1. **配置与代码分离的限流设计**：参数是运营态（压测调优/活动放量），注解只锚定位置——"改阈值要发版"的限流在真实事故面前等于没有
2. **双维度的正确形态**：不是"选 IP 还是用户"，是每维度独立配额（IP 宽用户严）；共享参数会让共享维度先耗尽——测试暴露设计缺陷的标准案例
3. **fail-open 的正确结构**：兜底 catch 不能包住业务调用，否则业务异常触发误重试——切面类容错的通式："判定可兜底，执行不兜底"
4. **AOP 代理的字段可见性**：代理对象字段全是 null，跨代理观察状态用静态成员或回调——Spring AOP 只代理方法不代理字段语义
5. **SPI 解耦宿主上下文**：框架不认识 UserHolder，但认识接口——依赖倒置在 starter 里的日常形态
6. **限流在拦截链的位置学**：限流(-200)→幂等(-100)→事务——被拒请求零副作用（连标记都不写），顺序错了就是脏数据
7. **名单的信任分级**：白名单（跳过一切）>封禁（直接拒）>限流（按规则）；静态（发版）+动态（Redis 即时）并集

**面试必问题**
1. "你们的限流怎么做的？"——注解声明场景+维度，参数全在配置中心化的 yml；Lua 原语（上迭代）+切面组装（本迭代）；拒绝 40008 枚举口径、细节进日志
2. "IP 限流和用户限流用哪个？"——都用，独立配额：IP 宽（NAT 后多人共享）用户严（单账号）；追问共享参数的坑→能讲现场发现的故事
3. "限流挂了怎么办？"——fail-open 放行+error 告警；强调"判定可兜底、执行不兜底"的结构（误重试=幂等灾难）
4. "X-Forwarded-For 能信吗？"——可伪造，网关覆盖后才可信；信任边界在入口
5. "限流和幂等谁先执行？"——限流在外层，被拒请求不写幂等标记零副作用；讲 @Order 数字与拦截链设计

## 6. 下一步

**M3.15 秒杀前置令牌：申请接口 + 一次性消费（Lua 原子校验删除）**——M3 最后一迭代。架构图②"发一次性令牌
(Redis, TTL 30s)"与④"令牌消费(Lua 原子 GET+DEL)"的兑现：令牌桶限流下发、秒杀接口消费前校验，
把"要不要让他进"从 Lua 扣减再前置一层——库存不认识没令牌的人。
