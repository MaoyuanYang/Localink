package com.localink.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.constant.KeyManage;
import com.localink.entity.Shop;
import com.localink.entity.User;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 恶意 Payload 安全矩阵（2026-10-02）：SQL 注入片段与 XSS 片段作为字段值
 * 落库-回读的往返安全。口径：参数化查询下 payload 只是普通字符串（往返一致、
 * 表结构无恙）；HTML 转义责任在前端（React 默认转义）与 ES 高亮（黑盒 S5n 已验）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PayloadSafetyIntegrationTest {

    private static final String PHONE = "13900139104";
    private static final String SQL_PAYLOAD = "x'); DROP TABLE lk_shop;--";
    private static final String XSS_PAYLOAD = "<script>alert(1)</script>";

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
        if (!createdShopIds.isEmpty()) {
            shopMapper.deleteBatchIds(createdShopIds);
            createdShopIds.forEach(id -> redisCache.delete(keyBuilder.build(KeyManage.SHOP_INFO, id)));
        }
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

    private long createShopNamed(String token, String name) throws Exception {
        String body = "{\"name\":\"" + name.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"typeId\":1,\"address\":\"payload-road\",\"avgPrice\":100,"
                + "\"longitude\":120.163,\"latitude\":30.274}";
        String resp = mockMvc.perform(post("/api/shop").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(resp.split("\"data\":\"")[1].split("\"")[0]);
        createdShopIds.add(id);
        return id;
    }

    @Test
    void sqlInjectionPayloadRoundTripsAsPlainText() throws Exception {
        String token = loginAndGetToken();
        long before = shopMapper.selectCount(null);
        long id = createShopNamed(token, SQL_PAYLOAD);
        long after = shopMapper.selectCount(null);
        assertEquals(before + 1, after, "仅新增一行");
        Shop stored = shopMapper.selectById(id);
        assertEquals(SQL_PAYLOAD, stored.getName(), "payload 应原样存储（参数化查询）");
        mockMvc.perform(get("/api/shop/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.name").value(SQL_PAYLOAD));
        assertNotEquals(0, shopMapper.selectCount(null), "lk_shop 表安然无恙");
    }

    @Test
    void xssPayloadRoundTripsAsPlainText() throws Exception {
        String token = loginAndGetToken();
        long id = createShopNamed(token, XSS_PAYLOAD);
        mockMvc.perform(get("/api/shop/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(XSS_PAYLOAD));
    }
}
