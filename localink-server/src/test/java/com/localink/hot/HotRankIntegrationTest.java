package com.localink.hot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.entity.PostComment;
import com.localink.entity.PostLike;
import com.localink.entity.User;
import com.localink.mapper.PostCommentMapper;
import com.localink.mapper.PostLikeMapper;
import com.localink.mapper.PostMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.HotRankService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * M6-E 热点统计验证：HLL 浏览 UV（登录用户去重/游客不计）、viewed 语义收敛（朴素+1 退役、
 * PFCOUNT 定时回写）、热榜分数模型（加权×半衰期衰减的数值与排序）、候选集窗口（近期∪现役、
 * 未过审跌出）、零互动帖不占榜、热榜接口、删帖级联（榜+UV）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HotRankIntegrationTest {

    private static final String PHONE_A = "13900139501";
    private static final String PHONE_B = "13900139502";

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
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private HotRankService hotRankService;

    private final List<Long> userIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();
    private String tokenA;
    private Long userIdA;

    @BeforeEach
    void setUp() throws Exception {
        commentMapper.delete(null);
        postLikeMapper.delete(null);
        postMapper.delete(null);
        redisCache.delete(keyBuilder.build(KeyManage.POST_HOT_TOP));
        redisCache.delete(keyBuilder.build(KeyManage.POST_LIKE_TOP));
        LoginUser a = loginAs(PHONE_A, "热榜测试员");
        tokenA = a.token();
        userIdA = a.userId();
    }

    @AfterEach
    void cleanup() {
        // UV key 按帖寻址且无 TTL 兜底：删表前先按帖子 id 清（防残留泄漏）
        postMapper.selectList(null).forEach(p ->
                redisCache.delete(keyBuilder.build(KeyManage.POST_UV, p.getId())));
        commentMapper.delete(null);
        postLikeMapper.delete(null);
        postMapper.delete(null);
        redisCache.delete(keyBuilder.build(KeyManage.POST_HOT_TOP));
        redisCache.delete(keyBuilder.build(KeyManage.POST_LIKE_TOP));
        for (String phone : new String[]{PHONE_A, PHONE_B}) {
            redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, phone));
            userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        }
        issuedTokens.forEach(t -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, t)));
    }

    @Test
    void uvDedupByLoginUserAndGuestNotCounted() throws Exception {
        Long postId = insertPostDirectly("UV 测试帖", 0, 0, LocalDateTime.now().minusHours(1));
        LoginUser b = loginAs(PHONE_B, "访客乙");

        // 同一登录用户多次浏览只记 1 个 UV
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", tokenA))
                    .andExpect(jsonPath("$.code").value(0));
        }
        assertEquals(1L, uv(postId));
        mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", b.token()))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(2L, uv(postId), "不同用户 UV+1");

        // 游客（无 token）可浏览详情但不计 UV
        mockMvc.perform(get("/api/post/{id}", postId))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(2L, uv(postId), "游客不计 UV");
    }

    @Test
    void naiveViewedRetiredAndSnapshotWritesBackUv() throws Exception {
        Long postId = insertPostDirectly("viewed 收敛帖", 0, 0, LocalDateTime.now().minusHours(1));
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", tokenA))
                    .andExpect(jsonPath("$.code").value(0));
        }
        assertEquals(0, postMapper.selectById(postId).getViewed().intValue(),
                "朴素 viewed+1 已退役（不再逐次累加）");

        hotRankService.runOnce();
        assertEquals(1, postMapper.selectById(postId).getViewed().intValue(),
                "快照任务 PFCOUNT 回写 viewed=UV");
    }

    @Test
    void hotScoreIsWeightedSumDecayedByHalfLife() throws Exception {
        // 恰好一个半衰期（72h）前的帖：衰减因子=0.5；liked=2/comments=1/uv=1 → (2×5+1×3+1×1)×0.5=7.0
        Long postId = insertPostDirectly("半衰期数值帖", 2, 1, LocalDateTime.now().minusHours(72));
        mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));

        LocalDateTime before = LocalDateTime.now();
        hotRankService.runOnce();
        Double score = redisCache.zsets().score(
                keyBuilder.build(KeyManage.POST_HOT_TOP), String.valueOf(postId));
        assertTrue(score != null, "应已入榜");
        double hours = java.time.Duration.between(before, LocalDateTime.now()).toMinutes() / 60.0 + 72;
        double expected = (2 * 5 + 1 * 3 + 1 * 1) * Math.exp(-Math.log(2) / 72 * hours);
        assertEquals(expected, score, 0.05, "score=(加权分)×e^(-λΔt)，λ=ln2/72h");
    }

    @Test
    void decayOrdersOldHighInteractionAboveNewLow() throws Exception {
        Long oldHot = insertPostDirectly("老帖高互动", 10, 0, LocalDateTime.now().minusHours(144));
        Long newLow = insertPostDirectly("新帖低互动", 1, 0, LocalDateTime.now().minusHours(1));
        Long newHot = insertPostDirectly("新帖高互动", 12, 0, LocalDateTime.now().minusHours(1));
        hotRankService.runOnce();

        String resp = mockMvc.perform(get("/api/post/hot").queryParam("limit", "10"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = com.alibaba.fastjson2.JSON.parseObject(resp).getJSONArray("data").stream()
                .map(o -> ((com.alibaba.fastjson2.JSONObject) o).getLong("id")).toList();
        // 两个半衰期(144h)后老帖 raw50×0.25=12.5 仍压过新帖低互动 raw5≈4.96，但压不过新帖高互动 raw60
        assertEquals(List.of(newHot, oldHot, newLow), ids, "加权×衰减后的综合排序");
    }

    @Test
    void candidateWindowKeepsCurrentMembersAndDropsUnauditedAndZeroScore() throws Exception {
        LocalDateTime outside = LocalDateTime.now().minusDays(30);
        Long currentOnRank = insertPostDirectly("窗口外现役榜帖", 5, 0, outside);
        Long outsideNotOnRank = insertPostDirectly("窗口外无人问津", 5, 0, outside);
        Long rejected = insertPostDirectly("被驳回帖", 9, 0, LocalDateTime.now().minusHours(1));
        Long zeroInteraction = insertPostDirectly("零互动新帖", 0, 0, LocalDateTime.now().minusMinutes(5));
        // 现役榜帖与被驳回帖手工放进现榜（模拟上一轮快照仍在榜）
        redisCache.zsets().add(keyBuilder.build(KeyManage.POST_HOT_TOP),
                String.valueOf(currentOnRank), 1.0);
        redisCache.zsets().add(keyBuilder.build(KeyManage.POST_HOT_TOP),
                String.valueOf(rejected), 1.0);
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, rejected).set(Post::getAuditStatus, 0));

        hotRankService.runOnce();
        assertTrue(redisCache.zsets().score(keyBuilder.build(KeyManage.POST_HOT_TOP),
                String.valueOf(currentOnRank)) != null, "现役榜帖保留（窗口外也不瞬断）");
        assertNull(redisCache.zsets().score(keyBuilder.build(KeyManage.POST_HOT_TOP),
                String.valueOf(outsideNotOnRank)), "窗口外且非现役不入榜");
        assertNull(redisCache.zsets().score(keyBuilder.build(KeyManage.POST_HOT_TOP),
                String.valueOf(rejected)), "未过审帖跌出（候选集过滤）");
        assertNull(redisCache.zsets().score(keyBuilder.build(KeyManage.POST_HOT_TOP),
                String.valueOf(zeroInteraction)), "零互动帖不占榜（score=0）");
    }

    @Test
    void hotEndpointRespectsLimitAndFiltersUnauditedOnBackfill() throws Exception {
        Long p1 = insertPostDirectly("高分帖", 10, 0, LocalDateTime.now().minusHours(1));
        Long p2 = insertPostDirectly("中分帖", 5, 0, LocalDateTime.now().minusHours(1));
        Long p3 = insertPostDirectly("低分帖", 1, 0, LocalDateTime.now().minusHours(1));
        hotRankService.runOnce();

        String resp = mockMvc.perform(get("/api/post/hot").queryParam("limit", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        List<Long> ids = com.alibaba.fastjson2.JSON.parseObject(resp).getJSONArray("data").stream()
                .map(o -> ((com.alibaba.fastjson2.JSONObject) o).getLong("id")).toList();
        assertEquals(List.of(p1, p2), ids, "limit 生效且按热度倒序");

        // 榜单有 member 但帖子被置未过审：回填时过滤（listOrdered），不炸接口
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, p1).set(Post::getAuditStatus, 0));
        String resp2 = mockMvc.perform(get("/api/post/hot").queryParam("limit", "10"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        List<Long> ids2 = com.alibaba.fastjson2.JSON.parseObject(resp2).getJSONArray("data").stream()
                .map(o -> ((com.alibaba.fastjson2.JSONObject) o).getLong("id")).toList();
        assertEquals(List.of(p2, p3), ids2, "未过审帖回填过滤");
    }

    @Test
    void deletePostCascadesHotRankAndUv() throws Exception {
        Long postId = insertPostDirectly("待删热榜帖", 3, 0, LocalDateTime.now().minusHours(1));
        mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        hotRankService.runOnce();
        assertTrue(redisCache.zsets().score(keyBuilder.build(KeyManage.POST_HOT_TOP),
                String.valueOf(postId)) != null);

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/post/{id}", postId)
                        .header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));
        assertNull(redisCache.zsets().score(keyBuilder.build(KeyManage.POST_HOT_TOP),
                String.valueOf(postId)), "删帖清榜");
        assertFalse(redisCache.hasKey(keyBuilder.build(KeyManage.POST_UV, postId)), "删帖清 UV");
    }

    // ===== 工具 =====

    private long uv(Long postId) {
        return redisCache.hyperloglogs().count(keyBuilder.build(KeyManage.POST_UV, postId));
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

    private Long insertPostDirectly(String title, int liked, int comments, LocalDateTime createTime) {
        Post post = new Post();
        post.setUserId(userIdA);
        post.setTitle(title);
        post.setImages("");
        post.setContent("内容-" + title);
        post.setLiked(liked);
        post.setComments(comments);
        post.setViewed(0);
        post.setAuditStatus(1);
        post.setCreateTime(createTime);
        postMapper.insert(post);
        return post.getId();
    }

    private record LoginUser(String token, Long userId) {
    }
}
