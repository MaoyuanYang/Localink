package com.localink.voucher;

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

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VoucherAuthIntegrationTest {

    private static final String PHONE = "13900139006";

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
    void claimWithoutTokenRejected() throws Exception {
        mockMvc.perform(post("/api/voucher/1/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void createWithoutTokenRejected() throws Exception {
        mockMvc.perform(post("/api/voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void createMissingTitleReturnsParamError() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(post("/api/voucher")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1,\"payValue\":0,\"actualValue\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void publicReadAllowed() throws Exception {
        mockMvc.perform(get("/api/voucher/list?shopId=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
    }
}
