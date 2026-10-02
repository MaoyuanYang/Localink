package com.localink.post;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.entity.User;
import com.localink.framework.auth.TokenRefreshInterceptor;
import com.localink.mapper.PostMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3 审核队列观测端点：auditStatus 筛选/全量/匿名拒绝。默认共享上下文。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminPostPageTest {

    private static final String PHONE = "13900139061";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<Long> createdPostIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdPostIds.forEach(postMapper::deleteById);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        if (user != null) {
            userMapper.deleteById(user.getId());
        }
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
    }

    @Test
    void adminPageFiltersByAuditStatus() throws Exception {
        User user = ensureUser();
        Long passedId = insertPost(user.getId(), "W3测试-在架帖", 1);
        Long rejectedId = insertPost(user.getId(), "W3测试-驳回帖", 2);

        mockMvc.perform(get("/api/post/admin/page").param("auditStatus", "2")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(String.valueOf(rejectedId)))
                .andExpect(jsonPath("$.data.records[0].auditStatus").value(2));

        mockMvc.perform(get("/api/post/admin/page")
                        .header(TokenRefreshInterceptor.AUTH_HEADER, loginAndGetToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].auditStatus").isNotEmpty());
    }

    @Test
    void adminPageAnonymousRejected() throws Exception {
        mockMvc.perform(get("/api/post/admin/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));
    }

    private User ensureUser() {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        if (user == null) {
            user = new User();
            user.setPhone(PHONE);
            user.setNickName("W3审核测试用户");
            user.setLevel(0);
            userMapper.insert(user);
            user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        }
        return user;
    }

    private Long insertPost(Long userId, String title, int auditStatus) {
        Post post = new Post();
        post.setUserId(userId);
        post.setTitle(title);
        post.setContent("W3 测试内容-" + System.nanoTime());
        post.setLiked(0);
        post.setComments(0);
        post.setViewed(0);
        post.setAuditStatus(auditStatus);
        post.setCreateTime(LocalDateTime.now().withNano(0));
        postMapper.insert(post);
        createdPostIds.add(post.getId());
        return post.getId();
    }

    private String loginAndGetToken() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        return token;
    }
}
