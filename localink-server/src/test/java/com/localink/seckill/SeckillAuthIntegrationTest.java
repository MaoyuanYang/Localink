package com.localink.seckill;

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
class SeckillAuthIntegrationTest {

    private static final String PHONE = "13900139008";

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
    void createWithoutTokenRejected() throws Exception {
        mockMvc.perform(post("/api/seckill-voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void createMissingStockReturnsParamError() throws Exception {
        String token = loginAndGetToken();
        String body = "{\"shopId\":1,\"title\":\"stock-missing\",\"payValue\":0,\"actualValue\":100,"
                + "\"minLevel\":0,\"beginTime\":\"2026-08-20 10:00:00\",\"endTime\":\"2026-08-21 10:00:00\"}";

        mockMvc.perform(post("/api/seckill-voucher")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void publicReadAllowed() throws Exception {
        mockMvc.perform(get("/api/seckill-voucher/list?shopId=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
    }
}
