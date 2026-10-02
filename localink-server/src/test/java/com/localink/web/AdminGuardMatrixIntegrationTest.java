package com.localink.web;

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

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 管理端点越权矩阵（2026-10-02）：以生产口径开启 @AdminOnly 闸门
 * （localink.security.admin-guard.enabled=true + 白名单手机号），验证
 * 匿名/普通用户/管理员三种身份在全部管理端点上的边界。
 * 覆盖 A-7 修复（M8）后的守卫语义与 W2/W3 新增管理端点的接入完整性。
 */
@SpringBootTest(properties = {
        "localink.security.admin-guard.enabled=true",
        "localink.security.admin-phones=13800138000"
})
@AutoConfigureMockMvc
class AdminGuardMatrixIntegrationTest {

    private static final String ADMIN_PHONE = "13800138000";
    private static final String CIVILIAN_PHONE = "13900139101";

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
        for (String phone : List.of(ADMIN_PHONE, CIVILIAN_PHONE)) {
            redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, phone));
        }
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        userMapper.delete(new LambdaQueryWrapper<User>()
                .in(User::getPhone, List.of(ADMIN_PHONE, CIVILIAN_PHONE)));
        UserHolder.clear();
    }

    private String loginAndGetToken(String phone) {
        smsService.sendCode(phone);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, phone));
        String token = userService.login(phone, code);
        issuedTokens.add(token);
        return token;
    }

    @Test
    void civilianRejectedOnPostAdminPage() throws Exception {
        String token = loginAndGetToken(CIVILIAN_PHONE);
        mockMvc.perform(get("/api/post/admin/page").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.FORBIDDEN.getCode()));
    }

    @Test
    void adminPassesOnPostAdminPage() throws Exception {
        String token = loginAndGetToken(ADMIN_PHONE);
        mockMvc.perform(get("/api/post/admin/page").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
    }

    @Test
    void civilianRejectedOnOrderAdminPage() throws Exception {
        String token = loginAndGetToken(CIVILIAN_PHONE);
        mockMvc.perform(get("/api/order/admin/page").param("voucherId", "1")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.FORBIDDEN.getCode()));
    }

    @Test
    void civilianRejectedOnManualCloseOrder() throws Exception {
        String token = loginAndGetToken(CIVILIAN_PHONE);
        mockMvc.perform(post("/api/order/admin/999999/close").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.FORBIDDEN.getCode()));
    }

    @Test
    void anonymousRejectedOnManualCloseOrder() throws Exception {
        mockMvc.perform(post("/api/order/admin/999999/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void anonymousRejectedOnAdminPageEvenThoughGetIsPublic() throws Exception {
        mockMvc.perform(get("/api/post/admin/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void civilianRejectedOnReconcileDashboard() throws Exception {
        String token = loginAndGetToken(CIVILIAN_PHONE);
        mockMvc.perform(get("/api/reconcile/admin/page").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.FORBIDDEN.getCode()));
        mockMvc.perform(get("/api/reconcile/admin/failures").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.FORBIDDEN.getCode()));
    }

    @Test
    void civilianRejectedOnSubscribeStats() throws Exception {
        String token = loginAndGetToken(CIVILIAN_PHONE);
        mockMvc.perform(get("/api/seckill-voucher/1/subscribe-stats").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.FORBIDDEN.getCode()));
    }

    @Test
    void adminPassesOnReconcileDashboard() throws Exception {
        String token = loginAndGetToken(ADMIN_PHONE);
        mockMvc.perform(get("/api/reconcile/admin/page").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()));
        // 券 1 在清扫后的库中不存在：40004 说明请求已越过权限闸门进入业务层（对照平民的 40003）
        mockMvc.perform(get("/api/seckill-voucher/1/subscribe-stats").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.NOT_FOUND.getCode()));
    }
}
