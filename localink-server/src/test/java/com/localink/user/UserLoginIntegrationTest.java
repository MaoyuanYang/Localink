package com.localink.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.SmsConstants;
import com.localink.constant.UserConstants;
import com.localink.entity.User;
import com.localink.mapper.UserMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class UserLoginIntegrationTest {

    private static final String PHONE = "13900139002";
    private static final String CODE_KEY = SmsConstants.CODE_KEY_PREFIX + PHONE;

    @Autowired
    private UserService userService;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final List<String> issuedTokens = new ArrayList<>();

    @AfterEach
    void cleanup() {
        stringRedisTemplate.delete(CODE_KEY);
        issuedTokens.forEach(token -> stringRedisTemplate.delete(UserConstants.TOKEN_KEY_PREFIX + token));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
    }

    @Test
    void firstLoginRegistersUserAndCreatesSession() {
        smsService.sendCode(PHONE);
        String code = stringRedisTemplate.opsForValue().get(CODE_KEY);

        String token = userService.login(PHONE, code);
        issuedTokens.add(token);

        assertNotNull(token);
        assertEquals(32, token.length());

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        assertNotNull(user);
        assertEquals("用户" + user.getId(), user.getNickName());

        String sessionKey = UserConstants.TOKEN_KEY_PREFIX + token;
        assertEquals(String.valueOf(user.getId()), stringRedisTemplate.opsForHash().get(sessionKey, UserConstants.FIELD_ID));
        assertEquals(PHONE, stringRedisTemplate.opsForHash().get(sessionKey, UserConstants.FIELD_PHONE));
        Long ttl = stringRedisTemplate.getExpire(sessionKey);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= UserConstants.SESSION_TTL_SECONDS);
    }

    @Test
    void wrongCodeRejectedAndCodeConsumed() {
        smsService.sendCode(PHONE);
        String real = stringRedisTemplate.opsForValue().get(CODE_KEY);
        assertNotNull(real);
        String wrong = real.charAt(0) == '9' ? "0" + real.substring(1) : "9" + real.substring(1);

        LocalinkException ex = assertThrows(LocalinkException.class, () -> userService.login(PHONE, wrong));
        assertEquals(BaseCode.SMS_CODE_INVALID.getCode(), ex.getCode());

        assertEquals(Boolean.FALSE, stringRedisTemplate.hasKey(CODE_KEY));
    }

    @Test
    void absentCodeTreatedAsExpired() {
        LocalinkException ex = assertThrows(LocalinkException.class, () -> userService.login(PHONE, "123456"));
        assertEquals(BaseCode.SMS_CODE_EXPIRED.getCode(), ex.getCode());
    }

    @Test
    void secondLoginReusesUserWithNewToken() {
        smsService.sendCode(PHONE);
        String token1 = userService.login(PHONE, stringRedisTemplate.opsForValue().get(CODE_KEY));
        issuedTokens.add(token1);

        smsService.sendCode(PHONE);
        String token2 = userService.login(PHONE, stringRedisTemplate.opsForValue().get(CODE_KEY));
        issuedTokens.add(token2);

        assertNotEquals(token1, token2);
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        assertEquals(1L, count);
    }
}
