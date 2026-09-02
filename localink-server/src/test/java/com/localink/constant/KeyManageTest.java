package com.localink.constant;

import com.localink.cache.KeyBuilder;
import com.localink.cache.config.CacheProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Key 治理契约测试：锁定完整 key 字符串与默认 TTL，防前缀漂移。
 */
class KeyManageTest {

    private final KeyBuilder keyBuilder = new KeyBuilder(new CacheProperties().getKeyPrefix());

    @Test
    void smsCodeKeyStaysCompatibleWithM1Format() {
        assertEquals("lk:sms:code:13900139000", keyBuilder.build(KeyManage.SMS_CODE, "13900139000").getKey());
    }

    @Test
    void userTokenKeyStaysCompatibleWithM1Format() {
        assertEquals("lk:user:token:0123abcd", keyBuilder.build(KeyManage.USER_TOKEN, "0123abcd").getKey());
    }

    @Test
    void shopInfoKeyFollowsDomainFirstStyle() {
        assertEquals("lk:shop:info:1", keyBuilder.build(KeyManage.SHOP_INFO, 1L).getKey());
    }

    @Test
    void governedTtlCarriedByEnum() {
        assertEquals(120, KeyManage.SMS_CODE.getTtl().toSeconds());
        assertEquals(1800, KeyManage.USER_TOKEN.getTtl().toSeconds());
    }

    @Test
    void shopInfoHasNoDefaultTtl() {
        assertNull(KeyManage.SHOP_INFO.getTtl());
    }
}
