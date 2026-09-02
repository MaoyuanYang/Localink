package com.localink.sms;

import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.service.SmsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SmsCodeIntegrationTest {

    private static final String PHONE = "13800138000";

    @Autowired
    private SmsService smsService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @AfterEach
    void cleanup() {
        redisCache.delete(smsCodeKey());
    }

    @Test
    void sendCodeStoresSixDigitsWithTtl() {
        smsService.sendCode(PHONE);

        String code = redisCache.strings().getString(smsCodeKey());
        assertNotNull(code);
        assertEquals(6, code.length());
        assertTrue(code.chars().allMatch(Character::isDigit));

        Long ttl = redisCache.getExpire(smsCodeKey());
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= KeyManage.SMS_CODE.getTtl().toSeconds());
    }

    @Test
    void resendWithinTtlRejected() {
        smsService.sendCode(PHONE);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> smsService.sendCode(PHONE));
        assertEquals(BaseCode.SMS_SEND_TOO_FREQUENT.getCode(), ex.getCode());
    }

    private KeyBuild smsCodeKey() {
        return keyBuilder.build(KeyManage.SMS_CODE, PHONE);
    }
}
