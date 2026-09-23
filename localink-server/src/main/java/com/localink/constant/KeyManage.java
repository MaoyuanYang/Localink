package com.localink.constant;

import com.localink.cache.KeyTemplate;
import lombok.Getter;

import java.time.Duration;

/**
 * 业务 Redis Key 唯一登记处：模板 + 默认 TTL + 语义说明（枚举即文档）。
 * 新增 key 只允许在此登记，经 KeyBuilder 生成，禁止散落硬编码。
 */
@Getter
public enum KeyManage implements KeyTemplate {

    /**
     * 手机号 → 短信验证码（一次性消费，登录即 GETDEL）。
     */
    SMS_CODE("sms:code:%s", Duration.ofSeconds(120), "手机号→短信验证码（String，一次性消费）"),

    /**
     * token → 用户会话 Hash（field：id/phone/nickName/icon/level）。
     */
    USER_TOKEN("user:token:%s", Duration.ofSeconds(1800), "token→用户会话Hash（id/phone/nickName/icon/level）"),

    /**
     * 商户 ID → 商户详情缓存（String JSON）。
     * M2.7 起正缓存为逻辑过期格式：LogicalExpiryEntry{data, expireTime}，无物理 TTL，expireTime=now+30min+随机[0,10min)，
     * 逻辑过期后异步重建、旧值兜底直返；key 不存在时走互斥锁同步重建回退。
     * 空值缓存复用本 key：DB 未命中写空串标记 + 2min+随机[0,30s) 短物理 TTL（穿透防护，随机 id 不会常驻）。
     */
    SHOP_INFO("shop:info:%s", null, "商户ID→商户详情缓存（String JSON，M2.7 逻辑过期格式 LogicalExpiryEntry{data,expireTime}，无物理 TTL，逻辑过期后异步重建旧值兜底；空值缓存复用本 key：空串标记+短物理TTL 带随机抖动）"),

    /**
     * 商户 ID → 缓存重建互斥锁。
     * M3.5 起实现由自研 SET NX EX 简单锁换为 Redisson 可重入锁 + 看门狗（经 DistributedLock 命令式调用，
     * key 仍由 KeyBuilder 生成）：释放有持有者校验、重建全程自动续期，根治"业务超时锁过期误删"。
     */
    SHOP_REBUILD_LOCK("shop:rebuild:lock:%s", null, "商户缓存重建互斥锁（Redisson 可重入锁+看门狗，M3.5 起替换自研简单锁；同步重建 wait 3s，异步选举 tryWithLock）"),

    /**
     * 商户 ID 布隆过滤器（Redisson RBloomFilter 位图 + {key}:config 参数哈希，无 TTL 跨重启保留）。
     * M2.9：启动全量灌入 + create 落库同步 add；detail() 前置拦截（不存在直接 NOT_FOUND，不进缓存/DB）。
     * 布隆不可删除：delete 商户后仍会通过布隆，落到空值缓存路径兜底（拦"曾经存在"）。
     * 模板与 application.yml 的 localink.cache.bloom.filters.shop.key-template 镜像，KeyManageTest 契约锁定。
     */
    SHOP_BLOOM("shop:bloom:id", null, "商户ID布隆过滤器（Redisson RBloomFilter，防穿透第一层：拦'从未存在'；无TTL，启动全量灌+create同步add，不可删→空值缓存兜底'曾经存在'）"),

    /**
     * 用户 ID → 秒杀一人一单分布式锁（Redisson 可重入锁 + 看门狗）。
     * M3.5 经 @ServiceLock(name="seckill:order", key=用户ID) 生成，实际 key = lk: + 本模板——
     * 注解值必须是编译期常量，无法引用枚举，故此处登记仅作文档对齐与 redis-cli 观测入口，不经 KeyBuilder 构建。
     */
    SECKILL_ORDER_LOCK("lock:seckill:order:%s", null, "秒杀一人一单用户维度锁（M3.5 引入，M3.6 Lua 原子判重后从热路径退役；登记保留作文档）"),

    /**
     * 券 ID → 秒杀库存（String，纯数字）。{voucherId} hash tag 与 SECKILL_ORDER_USERS 同槽，
     * Lua 多 key 原子执行的前提（Redis Cluster 下跨槽报错，单实例预留演进空间）。
     * 预热三时机：创建钩子 / 启动回灌（endTime 未到的全部券，以 DB 当前值为准覆盖）/ 运营更新；
     * TTL 由预热与每次扣减刷新为 now→endTime。
     */
    SECKILL_STOCK("seckill:stock:{%s}", null, "秒杀库存（String 数字，hash tag 同槽；Lua 原子扣减，返回 1 未预热由启动回灌兜底）"),

    /**
     * 券 ID → 已购用户集合（Set）。与 SECKILL_STOCK 同槽；Lua 内 SISMEMBER 判重 + SADD 登记，
     * 判重与扣减同脚本原子完成——M3.5 用户维度锁的替代者。
     */
    SECKILL_ORDER_USERS("seckill:order:{%s}", null, "秒杀已购用户集合（Set，hash tag 同槽，一人一单 Lua 原子判重）"),

    /**
     * 券 ID → 秒杀对账流水（Hash，field=traceId，value=JSON 账目）。与 SECKILL_STOCK 同槽，
     * 扣减/恢复 Lua 与账目变更同脚本原子写入（M3.12）。field 保留该笔资格的最新态
     * （logType 1 扣减 → 2 恢复），明细账在 lk_voucher_reconcile_log；TTL 跟随活动，活动后 Redis 活账清空。
     */
    SECKILL_FLOW("seckill:flow:{%s}", null, "秒杀对账流水（Hash，field=traceId→JSON 账目，hash tag 同槽；Lua 与扣减/恢复同原子写，M5.1 对账数据地基）"),

    /**
     * 幂等结果标记（String，JSON 结果，TTL 由注解 markerTtl 决定）。
     * 实际 key 由 idempotent-starter 前缀生成：lk:idem:marker:{name}:{key}——注解常量无法引用枚举，
     * 此登记为文档对齐与 redis-cli 观测入口（同 SECKILL_ORDER_LOCK 先例）。
     */
    IDEMPOTENT_MARKER("idem:marker:%s:%s", null, "幂等结果标记（@RepeatExecuteLimit 生成 lk:idem:marker:{name}:{key}，此处为文档对齐）"),

    /**
     * 幂等分布式公平锁（与 marker 同名空间）。实际 key：lk:idem:lock:{name}:{key}。
     */
    IDEMPOTENT_LOCK("idem:lock:%s:%s", null, "幂等分布式公平锁（@RepeatExecuteLimit 生成 lk:idem:lock:{name}:{key}，此处为文档对齐）"),

    /**
     * 券 ID + 用户 ID → 秒杀前置令牌（String，值=随机令牌，TTL 30s）。M3.15：
     * 申请接口经 @RateLimit 双维度闸门后发放（覆盖式，重申刷新旧令牌作废）；
     * 下单链路 Lua 原子 GET+DEL 一次性消费——库存不认识没令牌的人。
     */
    SECKILL_TOKEN("seckill:token:%s:%s", Duration.ofSeconds(30), "秒杀前置令牌（String 随机值，TTL 30s，Lua 原子一次性消费）"),

    /**
     * 限流状态 key（M3.13 ratelimit-starter 生成，前缀独立不经 KeyBuilder）：
     * 令牌桶 lk:rl:tb:{scene}:{dimension}（Hash：tokens/last）、滑动窗口 lk:rl:sw:{scene}:{dimension}
     * （ZSet：member=请求标识，score=毫秒）。空闲 TTL 自动清理（桶=补满耗时×2 下限 60s；窗=窗口长+60s）。
     * 登记为文档对齐与 redis-cli 观测入口（同 IDEMPOTENT_MARKER 先例）。
     */
    RATELIMIT_STATE("rl:%s:%s", null, "限流状态（ratelimit-starter 生成 lk:rl:tb:*/lk:rl:sw:*，此处为文档对齐）"),

    /**
     * 延迟队列（M5-B delay-starter 生成，Redisson 命名空间不经 KeyBuilder）：
     * lk:delay:{base}:{shard}——RDelayedQueue 绑定目标 RBlockingQueue，每分片一个消费线程。
     * 登记为文档对齐与 redis-cli 观测入口（同 RATELIMIT_STATE 先例）。
     */
    DELAY_QUEUE("delay:%s:%s", null, "延迟队列（delay-starter 生成 lk:delay:{base}:{shard}，此处为文档对齐）"),

    /**
     * 用户 ID → 通知收件箱（ZSet：member=通知 JSON{type,voucherId,beginTime}，score=毫秒）。
     * M5-C 预通知群发的落点；按 score 倒序即最新在前。教学口径的"触达"=收件箱+查询接口，
     * 渠道推送（钉钉/邮件）属 M7-B。TTL 30 天自清。
     */
    USER_NOTICE("user:notice:%s", Duration.ofDays(30), "用户通知收件箱（ZSet，member=通知JSON score=毫秒，M5-C）"),

    /**
     * 券 ID → 订阅排队（ZSet：member=userId，score=订阅时刻毫秒）——最早订阅者 score 最小，
     * popMin 原子弹出即"队首出队"。M5-C；状态另存 SUBSCRIBE_STATUS。
     */
    SUBSCRIBE_QUEUE("seckill:subscribe:%s", null, "秒杀券订阅排队（ZSet member=userId score=订阅毫秒，M5-C）"),

    /**
     * 券 ID → 订阅状态（Hash：field=userId，value=SUBSCRIBED/GRANTED）。
     * GRANTED=已被自动发券（回流补位成功）。
     */
    SUBSCRIBE_STATUS("seckill:subscribe:status:%s", null, "订阅状态（Hash field=userId→SUBSCRIBED/GRANTED，M5-C）"),

    /**
     * 店铺 ID + 日期 → 每日 Top 买家（ZSet：member=userId，score=当日成交单数）。
     * 建单事务内 ZINCRBY 维护；TTL 2 天按日自清；预通知附加圈选来源。
     */
    SHOP_TOP_BUYERS("shop:top:%s:%s", Duration.ofDays(2), "店铺每日Top买家（ZSet member=userId score=当日单数，M5-C）"),

    /**
     * 券 ID → 预通知已发标记（String，SETNX 防重）。延迟任务重投不重发群发的幂等闸门，TTL 1 天。
     */
    NOTICE_SENT("seckill:notice:sent:%s", Duration.ofDays(1), "预通知已发标记（SETNX 防重，M5-C）");

    private final String template;
    private final Duration ttl;
    private final String desc;

    KeyManage(String template, Duration ttl, String desc) {
        this.template = template;
        this.ttl = ttl;
        this.desc = desc;
    }

    @Override
    public String template() {
        return template;
    }
}
