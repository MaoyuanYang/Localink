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
     * 商户 ID → 商户详情 VO（String JSON，旁路缓存）。
     * 无默认 TTL：M2.3 调用方显式传 30 分钟，M2.5 雪崩抖动时传随机 TTL，故不登记默认值。
     * M2.4 空值缓存复用本 key：DB 未命中写空串标记 + 2 分钟短 TTL（穿透防护）。
     */
    SHOP_INFO("shop:info:%s", null, "商户ID→商户详情VO（String JSON，旁路缓存，TTL 由调用方显式传入；空值缓存复用本 key：空串标记+短TTL）");

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
