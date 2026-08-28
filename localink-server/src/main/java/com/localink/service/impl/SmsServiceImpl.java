package com.localink.service.impl;

import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsServiceImpl implements SmsService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_LENGTH = 6;

    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @Override
    public void sendCode(String phone) {
        KeyBuild key = keyBuilder.build(KeyManage.SMS_CODE, phone);
        if (redisCache.hasKey(key)) {
            throw new LocalinkException(BaseCode.SMS_SEND_TOO_FREQUENT);
        }
        String code = generateCode();
        redisCache.strings().set(key, code, KeyManage.SMS_CODE.getTtl());
        log.info("模拟发送短信验证码: phone={}, code={}", phone, code);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        return String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt(bound));
    }
}
