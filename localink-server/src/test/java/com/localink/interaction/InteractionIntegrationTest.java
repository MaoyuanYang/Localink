package com.localink.interaction;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Follow;
import com.localink.entity.Post;
import com.localink.entity.PostComment;
import com.localink.entity.PostLike;
import com.localink.entity.User;
import com.localink.mapper.FollowMapper;
import com.localink.mapper.PostCommentMapper;
import com.localink.mapper.PostLikeMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6-B 互动关系验证：帖子点赞（事实行+冗余计数+ZSet 榜单联动+幂等）、点赞榜（倒序/limit/
 * 未过审过滤/删帖级联）、评论点赞（仅计数）、关注/取关（双侧计数+Set 同步+幂等+边界拒绝）、
 * 共同关注（SINTER 交集+昵称回填+登录门槛）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InteractionIntegrationTest {

    private static final String PHONE_A = "13900139201";
    private static final String PHONE_B = "13900139202";
    private static final String PHONE_C = "13900139203";
    private static final String PHONE_D = "13900139204";
    private static final String PHONE_E = "13900139205";
    private static final String[] ALL_PHONES = {PHONE_A, PHONE_B, PHONE_C, PHONE_D, PHONE_E};

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
    private PostCommentMapper commentMapper;

    @Autowired
    private PostLikeMapper postLikeMapper;

    @Autowired
    private FollowMapper followMapper;

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
        // 社区四表测试独占：每例前清空；两个互动 Redis key 同步清理（user follow 集合随 userId 变化，残留即泄漏）
        commentMapper.delete(null);
        postLikeMapper.delete(null);
        followMapper.delete(null);
        postMapper.delete(null);
        redisCache.delete(keyBuilder.build(KeyManage.POST_LIKE_TOP));
        LoginUser a = loginAs(PHONE_A, "互动测试员");
        tokenA = a.token();
        userIdA = a.userId();
    }

    @AfterEach
    void cleanup() {
        commentMapper.delete(null);
        postLikeMapper.delete(null);
        followMapper.delete(null);
        postMapper.delete(null);
        redisCache.delete(keyBuilder.build(KeyManage.POST_LIKE_TOP));
        userIds.forEach(id -> redisCache.delete(keyBuilder.build(KeyManage.USER_FOLLOWEE, id)));
        for (String phone : ALL_PHONES) {
            redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, phone));
            userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        }
        issuedTokens.forEach(t -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, t)));
    }

    @Test
    void likeThenUnlikeJourneyWithIdempotency() throws Exception {
        Long postId = createPostDirectly("点赞旅程帖");

        mockMvc.perform(post("/api/post/{id}/like", postId).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(1));
        assertEquals(1L, postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId).eq(PostLike::getUserId, userIdA)));
        assertEquals(1, postMapper.selectById(postId).getLiked().intValue());
        assertEquals(1.0, redisCache.zsets().score(
                keyBuilder.build(KeyManage.POST_LIKE_TOP), String.valueOf(postId)));
        mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", tokenA))
                .andExpect(jsonPath("$.data.meLiked").value(true));

        // 重复点赞幂等：计数/事实行/榜单都不动
        mockMvc.perform(post("/api/post/{id}/like", postId).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(1));
        assertEquals(1L, postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId)));
        assertEquals(1, postMapper.selectById(postId).getLiked().intValue());

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/post/{id}/like", postId)
                        .header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(0));
        assertEquals(0L, postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId)));
        assertEquals(0, postMapper.selectById(postId).getLiked().intValue());
        // score 归零即 ZREM，榜单无僵尸 member
        assertNull(redisCache.zsets().score(
                keyBuilder.build(KeyManage.POST_LIKE_TOP), String.valueOf(postId)));
        mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", tokenA))
                .andExpect(jsonPath("$.data.meLiked").value(false));

        // 取消未赞：幂等
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/post/{id}/like", postId)
                        .header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    void likeTopReturnsRankOrderedAndRespectsLimitAndAuditFilter() throws Exception {
        Long p1 = createPostDirectly("一赞帖");
        Long p2 = createPostDirectly("二赞帖");
        Long p3 = createPostDirectly("零赞帖");
        LoginUser b = loginAs(PHONE_B, "路人乙");
        mockMvc.perform(post("/api/post/{id}/like", p1).header("Authorization", tokenA))
                .andExpect(jsonPath("$.data").value(1));
        mockMvc.perform(post("/api/post/{id}/like", p2).header("Authorization", tokenA))
                .andExpect(jsonPath("$.data").value(1));
        mockMvc.perform(post("/api/post/{id}/like", p2).header("Authorization", b.token()))
                .andExpect(jsonPath("$.data").value(2));

        mockMvc.perform(get("/api/post/like/top").header("Authorization", tokenA)
                        .queryParam("limit", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(p2))
                .andExpect(jsonPath("$.data[1].id").value(p1));

        // 未过审帖不回填（榜单有 member，但 listOrdered 过滤 audit）
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, p2).set(Post::getAuditStatus, 0));
        mockMvc.perform(get("/api/post/like/top").header("Authorization", tokenA)
                        .queryParam("limit", "10"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(p1));
    }

    @Test
    void deletePostCascadesLikesAndRank() throws Exception {
        Long postId = createPostDirectly("待删点赞帖");
        mockMvc.perform(post("/api/post/{id}/like", postId).header("Authorization", tokenA))
                .andExpect(jsonPath("$.data").value(1));
        assertEquals(1.0, redisCache.zsets().score(
                keyBuilder.build(KeyManage.POST_LIKE_TOP), String.valueOf(postId)));

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/post/{id}", postId)
                        .header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0L, postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId)));
        assertNull(redisCache.zsets().score(
                keyBuilder.build(KeyManage.POST_LIKE_TOP), String.valueOf(postId)));
        mockMvc.perform(get("/api/post/like/top").header("Authorization", tokenA))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void commentLikeCountsOnlyWithoutRank() throws Exception {
        Long postId = createPostDirectly("评论点赞帖");
        Long commentId = insertCommentDirectly(postId);
        mockMvc.perform(post("/api/post/comment/{id}/like", commentId)
                        .header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(1));
        // 无事实表：同一人可再赞（database.md 4.8 决策，与帖子点赞的防重形成档位对比）
        mockMvc.perform(post("/api/post/comment/{id}/like", commentId)
                        .header("Authorization", tokenA))
                .andExpect(jsonPath("$.data").value(2));
        assertEquals(2, commentMapper.selectById(commentId).getLiked().intValue());
        assertNull(redisCache.zsets().score(
                keyBuilder.build(KeyManage.POST_LIKE_TOP), String.valueOf(postId)), "评论点赞不入帖榜");
    }

    @Test
    void followUnfollowJourneyWithIdempotency() throws Exception {
        LoginUser b = loginAs(PHONE_B, "探店达人B");
        mockMvc.perform(post("/api/follow/{id}", b.userId()).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1L, followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userIdA).eq(Follow::getFollowUserId, b.userId())));
        assertEquals(1, userMapper.selectById(userIdA).getFollowee().intValue());
        assertEquals(1, userMapper.selectById(b.userId()).getFans().intValue());
        assertTrue(redisCache.sets().isMember(
                keyBuilder.build(KeyManage.USER_FOLLOWEE, userIdA), String.valueOf(b.userId())));

        // 重复关注幂等：关系行/双侧计数/Set 都不动
        mockMvc.perform(post("/api/follow/{id}", b.userId()).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, userMapper.selectById(userIdA).getFollowee().intValue());

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/follow/{id}", b.userId())
                        .header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0L, followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userIdA).eq(Follow::getFollowUserId, b.userId())));
        assertEquals(0, userMapper.selectById(userIdA).getFollowee().intValue());
        assertEquals(0, userMapper.selectById(b.userId()).getFans().intValue());
        assertFalse(redisCache.sets().isMember(
                keyBuilder.build(KeyManage.USER_FOLLOWEE, userIdA), String.valueOf(b.userId())));

        // 取关未关注：幂等
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/follow/{id}", b.userId())
                        .header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, userMapper.selectById(userIdA).getFollowee().intValue());
    }

    @Test
    void followSelfAndMissingUserRejected() throws Exception {
        mockMvc.perform(post("/api/follow/{id}", userIdA).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(40001));
        mockMvc.perform(post("/api/follow/{id}", 999999L).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(40004));
        assertEquals(0L, followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userIdA)));
    }

    @Test
    void commonFollowsIntersectsAndRequiresLogin() throws Exception {
        LoginUser b = loginAs(PHONE_B, "对端用户B");
        LoginUser c = loginAs(PHONE_C, "川菜博主");
        LoginUser d = loginAs(PHONE_D, "甜品博主");
        LoginUser e = loginAs(PHONE_E, "只有A关注");
        mockMvc.perform(post("/api/follow/{id}", c.userId()).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/follow/{id}", d.userId()).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/follow/{id}", e.userId()).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/follow/{id}", c.userId()).header("Authorization", b.token()))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/follow/{id}", d.userId()).header("Authorization", b.token()))
                .andExpect(jsonPath("$.code").value(0));

        String resp = mockMvc.perform(get("/api/follow/common/{id}", b.userId())
                        .header("Authorization", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        List<Long> commonIds = com.alibaba.fastjson2.JSON.parseArray(
                        com.alibaba.fastjson2.JSON.parseObject(resp).getString("data"))
                .stream().map(o -> ((com.alibaba.fastjson2.JSONObject) o).getLong("userId")).toList();
        assertTrue(commonIds.contains(c.userId()) && commonIds.contains(d.userId()), "交集=C、D");
        assertFalse(commonIds.contains(e.userId()), "E 仅 A 关注，不在交集");
        String nickC = com.alibaba.fastjson2.JSON.parseArray(
                        com.alibaba.fastjson2.JSON.parseObject(resp).getString("data"))
                .stream().map(o -> (com.alibaba.fastjson2.JSONObject) o)
                .filter(o -> o.getLong("userId").equals(c.userId())).findFirst().orElseThrow()
                .getString("nickName");
        assertEquals("川菜博主", nickC, "交集昵称回填");

        // 未登录（GET 不经登录拦截器）：服务内显式拒绝
        mockMvc.perform(get("/api/follow/common/{id}", b.userId()))
                .andExpect(jsonPath("$.code").value(40002));
    }

    private LoginUser loginAs(String phone, String nickName) {
        smsService.sendCode(phone);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, phone));
        String token = userService.login(phone, code);
        issuedTokens.add(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId()).set(User::getNickName, nickName));
        userIds.add(user.getId());
        return new LoginUser(token, user.getId());
    }

    private Long createPostDirectly(String title) {
        Post post = new Post();
        post.setUserId(userIdA);
        post.setShopId(1L);
        post.setTitle(title);
        post.setImages("");
        post.setContent("直插内容-" + System.nanoTime());
        post.setLiked(0);
        post.setComments(0);
        post.setViewed(0);
        post.setAuditStatus(1);
        postMapper.insert(post);
        return post.getId();
    }

    private Long insertCommentDirectly(Long postId) {
        PostComment comment = new PostComment();
        comment.setPostId(postId);
        comment.setUserId(userIdA);
        comment.setParentId(0L);
        comment.setReplyId(0L);
        comment.setContent("直插评论-" + System.nanoTime());
        comment.setLiked(0);
        comment.setAuditStatus(1);
        commentMapper.insert(comment);
        return comment.getId();
    }

    private record LoginUser(String token, Long userId) {
    }
}
