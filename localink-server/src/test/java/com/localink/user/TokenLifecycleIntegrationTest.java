package com.localink.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.constant.KeyManage;
import com.localink.entity.User;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.UserMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 会话生命周期矩阵（2026-10-02）：30 分钟滑动 token 的过期、注销失效与滑动续期。
 * 续期验证采用"先压短 TTL、中途访问、超过原 TTL 后仍有效"的行为学口径，
 * 避免直接断言 Redis TTL 数值（KeyManage 文档口径 1800s）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TokenLifecycleIntegrationTest {

    private static final String PHONE = "13900139103";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

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
        UserHolder.clear();
    }

    private String loginAndGetToken() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        return token;
    }

    @Test
    void freshTokenWorksOnMe() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(get("/api/user/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
    }

    @Test
    void expiredTokenRejected() throws Exception {
        String token = loginAndGetToken();
        redisCache.expire(keyBuilder.build(KeyManage.USER_TOKEN, token), Duration.ofSeconds(2));
        Thread.sleep(2500);
        mockMvc.perform(get("/api/user/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void tokenInvalidatedAfterLogout() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(get("/api/user/me").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
        mockMvc.perform(delete("/api/user/logout").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
        mockMvc.perform(get("/api/user/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void slidingRefreshExtendsSessionBeyondOriginalTtl() throws Exception {
        String token = loginAndGetToken();
        // 压短 TTL 到 3s：若没有滑动续期，3.5s 后必失效
        redisCache.expire(keyBuilder.build(KeyManage.USER_TOKEN, token), Duration.ofSeconds(3));
        Thread.sleep(1500);
        mockMvc.perform(get("/api/user/me").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
        Thread.sleep(2500);
        mockMvc.perform(get("/api/user/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
    }
}
