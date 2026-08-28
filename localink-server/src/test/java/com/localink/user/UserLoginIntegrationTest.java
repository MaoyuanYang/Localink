package com.localink.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.constant.UserConstants;
import com.localink.entity.User;
import com.localink.mapper.UserMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class UserLoginIntegrationTest {

    private static final String PHONE = "13900139002";

    @Autowired
    private UserService userService;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<String> issuedTokens = new ArrayList<>();

    @AfterEach
    void cleanup() {
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
    }

    @Test
    void firstLoginRegistersUserAndCreatesSession() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));

        String token = userService.login(PHONE, code);
        issuedTokens.add(token);

        assertNotNull(token);
        assertEquals(32, token.length());

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        assertNotNull(user);
        assertEquals("用户" + user.getId(), user.getNickName());

        KeyBuild sessionKey = keyBuilder.build(KeyManage.USER_TOKEN, token);
        assertEquals(String.valueOf(user.getId()), redisCache.hashes().get(sessionKey, UserConstants.FIELD_ID, String.class));
        assertEquals(PHONE, redisCache.hashes().get(sessionKey, UserConstants.FIELD_PHONE, String.class));
        Long ttl = redisCache.getExpire(sessionKey);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= KeyManage.USER_TOKEN.getTtl().toSeconds());
    }

    @Test
    void wrongCodeRejectedAndCodeConsumed() {
        smsService.sendCode(PHONE);
        String real = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        assertNotNull(real);
        String wrong = real.charAt(0) == '9' ? "0" + real.substring(1) : "9" + real.substring(1);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> userService.login(PHONE, wrong));
        assertEquals(BaseCode.SMS_CODE_INVALID.getCode(), ex.getCode());

        assertFalse(redisCache.hasKey(keyBuilder.build(KeyManage.SMS_CODE, PHONE)));
    }

    @Test
    void absentCodeTreatedAsExpired() {
        LocalinkException ex = assertThrows(LocalinkException.class, () -> userService.login(PHONE, "123456"));
        assertEquals(BaseCode.SMS_CODE_EXPIRED.getCode(), ex.getCode());
    }

    @Test
    void secondLoginReusesUserWithNewToken() {
        smsService.sendCode(PHONE);
        String token1 = userService.login(PHONE, redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE)));
        issuedTokens.add(token1);

        smsService.sendCode(PHONE);
        String token2 = userService.login(PHONE, redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE)));
        issuedTokens.add(token2);

        assertNotEquals(token1, token2);
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        assertEquals(1L, count);
    }
}
