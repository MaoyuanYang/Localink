package com.localink.feed;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.entity.User;
import com.localink.mapper.PostMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * M6-C 收件箱截断：inbox-max-size 覆盖为 3（@SpringBootTest properties 优先级高于
 * application.yml），发帖推送后超上限 popMin——收件箱只保留最新 3 条，老帖淘汰。
 * 单独成类：properties 是类级配置，不与默认阈值的用例混跑。
 */
// properties 覆盖会产生独立 ApplicationContext（多占 2 个数据源连接池）；跑完即关闭，
// 避免全量运行时把本机 MySQL max_connections 挤爆（实测曾殃及后续 shop 测试类建上下文）
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = "localink.feed.inbox-max-size=3")
@AutoConfigureMockMvc
class FeedInboxTrimIntegrationTest {

    private static final String PHONE_A = "13900139311";  // 粉丝
    private static final String PHONE_B = "13900139312";  // 作者

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
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<Long> userIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();
    private String tokenA;
    private Long userIdA;

    @BeforeEach
    void setUp() throws Exception {
        postMapper.delete(null);
        LoginUser a = loginAs(PHONE_A, "追更粉丝");
        tokenA = a.token();
        userIdA = a.userId();
        LoginUser b = loginAs(PHONE_B, "高频作者");
        mockMvc.perform(post("/api/follow/{id}", b.userId()).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        for (int i = 1; i <= 5; i++) {
            createPostViaApi(b.token(), "第" + i + "更");
        }
    }

    @AfterEach
    void cleanup() {
        postMapper.delete(null);
        userIds.forEach(id -> {
            redisCache.delete(keyBuilder.build(KeyManage.USER_FEED, id));
            redisCache.delete(keyBuilder.build(KeyManage.USER_FOLLOWEE, id));
        });
        for (String phone : new String[]{PHONE_A, PHONE_B}) {
            redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, phone));
            userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        }
        issuedTokens.forEach(t -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, t)));
    }

    @Test
    void inboxTrimmedToConfiguredMaxAndFeedSeesLatestOnly() throws Exception {
        assertEquals(3L, redisCache.zsets().size(keyBuilder.build(KeyManage.USER_FEED, userIdA)),
                "超上限 popMin 截断");

        String resp = mockMvc.perform(get("/api/feed?size=10").header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        com.alibaba.fastjson2.JSONArray records = com.alibaba.fastjson2.JSON.parseObject(resp)
                .getJSONObject("data").getJSONArray("records");
        assertEquals(3, records.size(), "只剩最新 3 条");
        List<Long> visibleIds = records.stream()
                .map(o -> ((com.alibaba.fastjson2.JSONObject) o).getLong("id")).toList();
        // 最新 3 条=第 3/4/5 更（按发帖倒序），最老的 2 条已被截断淘汰
        List<Long> latest = postMapper.selectList(new LambdaQueryWrapper<Post>()
                        .orderByDesc(Post::getCreateTime)
                        .orderByDesc(Post::getId)).stream()
                .limit(3).map(Post::getId).toList();
        assertEquals(latest, visibleIds);
        assertNull(com.alibaba.fastjson2.JSON.parseObject(resp).getJSONObject("data").getLong("nextCursor"));
    }

    private LoginUser loginAs(String phone, String nickName) {
        smsService.sendCode(phone);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, phone));
        String token = userService.login(phone, code);
        issuedTokens.add(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId()).set(User::getNickName, nickName));
        userIds.add(user.getId());
        return new LoginUser(token, user.getId());
    }

    private void createPostViaApi(String token, String title) throws Exception {
        mockMvc.perform(post("/api/post").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"" + title + "-内容\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private record LoginUser(String token, Long userId) {
    }
}
