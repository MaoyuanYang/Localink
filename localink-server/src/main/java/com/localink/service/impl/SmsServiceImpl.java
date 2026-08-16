package com.localink.service.impl;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.SmsConstants;
import com.localink.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsServiceImpl implements SmsService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void sendCode(String phone) {
        String key = SmsConstants.CODE_KEY_PREFIX + phone;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            throw new LocalinkException(BaseCode.SMS_SEND_TOO_FREQUENT);
        }
        String code = generateCode();
        stringRedisTemplate.opsForValue().set(key, code, Duration.ofSeconds(SmsConstants.CODE_TTL_SECONDS));
        log.info("模拟发送短信验证码: phone={}, code={}", phone, code);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, SmsConstants.CODE_LENGTH);
        return String.format("%0" + SmsConstants.CODE_LENGTH + "d", RANDOM.nextInt(bound));
    }
}
