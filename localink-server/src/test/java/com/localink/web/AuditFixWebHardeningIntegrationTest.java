package com.localink.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.dto.PostCreateDTO;
import com.localink.api.dto.UserDTO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.entity.User;
import com.localink.framework.auth.AdminGuardInterceptor;
import com.localink.framework.auth.AdminOnly;
import com.localink.framework.auth.TokenRefreshInterceptor;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.PostMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.PostService;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 审计修复验证（docs/VERIFICATION.md B-3/B-4/B-19/D-14 与 A-7 权限闸门）：
 * 游客 GET 通知/订阅状态应答 40002（原 NPE→500）；登出后 token 立即失效；
 * 经纬度范围与券金额关系校验；DFA 分域扫描不再跨字段误杀；@AdminOnly 白名单闸门。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditFixWebHardeningIntegrationTest {

    private static final String PHONE = "13900139882";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SmsService smsService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private PostService postService;
    @Autowired
    private RedisCache redisCache;
    @Autowired
    private KeyBuilder keyBuilder;

    private String token;
    private Long userId;

    @BeforeEach
    void login() {
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        token = userService.login(PHONE, code);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        userId = user.getId();
    }

    @AfterEach
    void cleanup() {
        UserHolder.clear();
        postMapper.delete(new LambdaQueryWrapper<Post>().eq(Post::getUserId, userId));
        redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
    }

    @Test
    void anonymousNoticeReturnsUnauthorizedNot500() throws Exception {
        mockMvc.perform(get("/api/notice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void anonymousSubscribeStatusReturnsUnauthorizedNot500() throws Exception {
        mockMvc.perform(get("/api/seckill-voucher/1/subscribe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void logoutInvalidatesTokenImmediately() throws Exception {
        mockMvc.perform(delete("/api/user/logout")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/user/me")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void shopLatitudeOutOfRangeRejected() throws Exception {
        mockMvc.perform(post("/api/shop")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"M8越界商户\",\"typeId\":1,\"address\":\"x\","
                                + "\"avgPrice\":100,\"longitude\":120.1,\"latitude\":200.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void voucherValueOrderRejected() throws Exception {
        String begin = java.time.LocalDateTime.now().plusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String end = java.time.LocalDateTime.now().plusDays(2)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        mockMvc.perform(post("/api/seckill-voucher")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1,\"title\":\"M8负价值券\",\"payValue\":10000,"
                                + "\"actualValue\":100,\"stock\":5,\"minLevel\":0,"
                                + "\"beginTime\":\"" + begin + "\",\"endTime\":\"" + end + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void dfaCrossFieldConcatenationNoLongerRejected() {
        UserDTO holderUser = new UserDTO();
        holderUser.setId(userId);
        holderUser.setLevel(0);
        UserHolder.set(holderUser);
        PostCreateDTO dto = new PostCreateDTO();
        // 标题尾字"赌"+正文首字"博"在旧实现拼接为"赌博"被误杀；分域扫描后应放行
        dto.setShopId(1L);
        dto.setTitle("M8边界记录，最后一字是赌");
        dto.setContent("博学多才的正文，与敏感词无关");
        String postId = postService.create(dto);
        assertTrue(Long.parseLong(postId) > 0, "跨字段拼接不得误杀");
        // 走删帖链路清理：同步删除 ES 文档（直接删 DB 会留 ES 孤儿文档）
        postService.delete(Long.parseLong(postId));
    }

    @Test
    void adminGuardBlocksNonAdminWhenEnabled() throws Exception {
        Method adminMethod = GuardDummy.class.getDeclaredMethod("adminWrite");
        HandlerMethod handlerMethod = new HandlerMethod(new GuardDummy(), adminMethod);

        AdminGuardInterceptor enabled = new AdminGuardInterceptor(true, "13900000001");
        UserDTO holderUser = new UserDTO();
        holderUser.setId(userId);
        holderUser.setPhone(PHONE);
        UserHolder.set(holderUser);

        LocalinkException denied = assertThrows(LocalinkException.class,
                () -> enabled.preHandle(
                        new org.springframework.mock.web.MockHttpServletRequest(),
                        new org.springframework.mock.web.MockHttpServletResponse(), handlerMethod));
        assertEquals(BaseCode.FORBIDDEN.getCode(), denied.getCode());

        AdminGuardInterceptor disabled = new AdminGuardInterceptor(false, "");
        assertTrue(disabled.preHandle(
                new org.springframework.mock.web.MockHttpServletRequest(),
                new org.springframework.mock.web.MockHttpServletResponse(), handlerMethod),
                "默认关闭态全放行（演示单机口径）");

        AdminGuardInterceptor enabledWithMe = new AdminGuardInterceptor(true, PHONE);
        assertTrue(enabledWithMe.preHandle(
                new org.springframework.mock.web.MockHttpServletRequest(),
                new org.springframework.mock.web.MockHttpServletResponse(), handlerMethod),
                "白名单手机号放行");
    }

    private static final class GuardDummy {
        @AdminOnly
        public void adminWrite() {
        }
    }
}
