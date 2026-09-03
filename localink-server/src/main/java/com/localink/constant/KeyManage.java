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
     * 商户 ID → 缓存重建互斥锁（值固定 "1"，SET NX EX 原子抢锁 + DEL 释放）。
     * 自研简单锁：无持有者标识，业务执行超过锁 TTL 时释放会误删他人锁——留待 M3.3 Redisson 演进。
     */
    SHOP_REBUILD_LOCK("shop:rebuild:lock:%s", Duration.ofSeconds(10), "商户缓存重建互斥锁（SET NX EX 自研简单锁，锁超时兜底防死锁）"),

    /**
     * 商户 ID 布隆过滤器（Redisson RBloomFilter 位图 + {key}:config 参数哈希，无 TTL 跨重启保留）。
     * M2.9：启动全量灌入 + create 落库同步 add；detail() 前置拦截（不存在直接 NOT_FOUND，不进缓存/DB）。
     * 布隆不可删除：delete 商户后仍会通过布隆，落到空值缓存路径兜底（拦"曾经存在"）。
     * 模板与 application.yml 的 localink.cache.bloom.filters.shop.key-template 镜像，KeyManageTest 契约锁定。
     */
    SHOP_BLOOM("shop:bloom:id", null, "商户ID布隆过滤器（Redisson RBloomFilter，防穿透第一层：拦'从未存在'；无TTL，启动全量灌+create同步add，不可删→空值缓存兜底'曾经存在'）");

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
