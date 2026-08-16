package com.localink.sms;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.SmsConstants;
import com.localink.service.SmsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SmsCodeIntegrationTest {

    private static final String PHONE = "13800138000";
    private static final String KEY = SmsConstants.CODE_KEY_PREFIX + PHONE;

    @Autowired
    private SmsService smsService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanup() {
        stringRedisTemplate.delete(KEY);
    }

    @Test
    void sendCodeStoresSixDigitsWithTtl() {
        smsService.sendCode(PHONE);

        String code = stringRedisTemplate.opsForValue().get(KEY);
        assertNotNull(code);
        assertEquals(SmsConstants.CODE_LENGTH, code.length());
        assertTrue(code.chars().allMatch(Character::isDigit));

        Long ttl = stringRedisTemplate.getExpire(KEY);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= SmsConstants.CODE_TTL_SECONDS);
    }

    @Test
    void resendWithinTtlRejected() {
        smsService.sendCode(PHONE);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> smsService.sendCode(PHONE));
        assertEquals(BaseCode.SMS_SEND_TOO_FREQUENT.getCode(), ex.getCode());
    }
}
