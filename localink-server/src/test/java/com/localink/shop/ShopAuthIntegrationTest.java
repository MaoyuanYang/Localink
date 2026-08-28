package com.localink.shop;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.constant.KeyManage;
import com.localink.entity.User;
import com.localink.framework.auth.TokenRefreshInterceptor;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.ShopMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShopAuthIntegrationTest {

    private static final String PHONE = "13900139004";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<String> issuedTokens = new ArrayList<>();
    private final List<Long> createdShopIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        createdShopIds.forEach(shopMapper::deleteById);
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
    void writeWithoutTokenRejected() throws Exception {
        mockMvc.perform(post("/api/shop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));

        mockMvc.perform(post("/api/shop-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void readWithoutTokenAllowed() throws Exception {
        mockMvc.perform(get("/api/shop/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));

        mockMvc.perform(get("/api/shop-type/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
    }

    @Test
    void createShopWithTokenSucceeds() throws Exception {
        String token = loginAndGetToken();
        String body = "{\"name\":\"鉴权测试商户\",\"typeId\":10,\"images\":\"/images/auth-test.jpg\",\"address\":\"鉴权测试地址\",\"longitude\":120.1,\"latitude\":30.1}";

        MvcResult result = mockMvc.perform(post("/api/shop")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()))
                .andReturn();

        JsonNode root = new ObjectMapper().readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Long shopId = Long.valueOf(root.get("data").asText());
        createdShopIds.add(shopId);
    }

    @Test
    void createShopMissingNameReturnsParamError() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(post("/api/shop")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }
}
