package com.localink.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.common.code.BaseCode;
import com.localink.constant.SmsConstants;
import com.localink.constant.UserConstants;
import com.localink.entity.User;
import com.localink.framework.auth.TokenRefreshInterceptor;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.UserMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthInterceptorIntegrationTest {

    private static final String PHONE = "13900139003";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final List<String> issuedTokens = new ArrayList<>();

    @AfterEach
    void cleanup() {
        stringRedisTemplate.delete(SmsConstants.CODE_KEY_PREFIX + PHONE);
        issuedTokens.forEach(token -> stringRedisTemplate.delete(UserConstants.TOKEN_KEY_PREFIX + token));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        UserHolder.clear();
    }

    private String loginAndGetToken() {
        smsService.sendCode(PHONE);
        String code = stringRedisTemplate.opsForValue().get(SmsConstants.CODE_KEY_PREFIX + PHONE);
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        return token;
    }

    @Test
    void meWithValidTokenReturnsUserWithIdAsString() throws Exception {
        String token = loginAndGetToken();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        assertNotNull(user);

        mockMvc.perform(get("/api/user/me").header(TokenRefreshInterceptor.AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.id").value(String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.phone").value(PHONE))
                .andExpect(jsonPath("$.data.nickName").value("用户" + user.getId()))
                .andExpect(jsonPath("$.data.level").value(0));
    }

    @Test
    void meWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/user/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void meWithForgedTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/user/me").header(TokenRefreshInterceptor.AUTH_HEADER, "forged00000000000000000000000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void authenticatedRequestRefreshesSessionTtl() throws Exception {
        String token = loginAndGetToken();
        String sessionKey = UserConstants.TOKEN_KEY_PREFIX + token;
        stringRedisTemplate.expire(sessionKey, Duration.ofSeconds(100));

        mockMvc.perform(get("/api/user/me").header(TokenRefreshInterceptor.AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));

        Long ttl = stringRedisTemplate.getExpire(sessionKey);
        assertNotNull(ttl);
        assertTrue(ttl > 100 && ttl <= UserConstants.SESSION_TTL_SECONDS);
    }

    @Test
    void loginEndpointNotBlockedByInterceptors() throws Exception {
        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }
}
