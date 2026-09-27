package com.localink.feed;

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
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * M6-C Feed 流验证：推模式（发帖事件→粉丝收件箱，非关注者不收）、score 游标滚动分页
 * （不重不漏、nextCursor 到底 null）、推挽结合（大 V 不推读时拉+归并顺序）、取关读端过滤、
 * 同秒位账唯一、未登录拒绝与 audit 过滤不阻塞翻页。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedIntegrationTest {

    private static final String PHONE_A = "13900139301";  // 读端（粉丝）
    private static final String PHONE_B = "13900139302";  // 普通作者（推）
    private static final String PHONE_C = "13900139303";  // 路人（非关注者）
    private static final String PHONE_V = "13900139304";  // 大 V 作者（拉）
    private static final String[] ALL_PHONES = {PHONE_A, PHONE_B, PHONE_C, PHONE_V};

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
        // 社区表测试独占：每例前清空；互动相关 Redis key 同步清理（feed 集合随 userId 变化，残留即泄漏）
        commentMapper.delete(null);
        postLikeMapper.delete(null);
        followMapper.delete(null);
        postMapper.delete(null);
        userIds.forEach(id -> {
            redisCache.delete(keyBuilder.build(KeyManage.USER_FEED, id));
            redisCache.delete(keyBuilder.build(KeyManage.USER_FOLLOWEE, id));
        });
        redisCache.delete(keyBuilder.build(KeyManage.POST_LIKE_TOP));
        LoginUser a = loginAs(PHONE_A, "刷帖狂魔");
        tokenA = a.token();
        userIdA = a.userId();
    }

    @AfterEach
    void cleanup() {
        commentMapper.delete(null);
        postLikeMapper.delete(null);
        followMapper.delete(null);
        postMapper.delete(null);
        userIds.forEach(id -> {
            redisCache.delete(keyBuilder.build(KeyManage.USER_FEED, id));
            redisCache.delete(keyBuilder.build(KeyManage.USER_FOLLOWEE, id));
        });
        redisCache.delete(keyBuilder.build(KeyManage.POST_LIKE_TOP));
        for (String phone : ALL_PHONES) {
            redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, phone));
            userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        }
        issuedTokens.forEach(t -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, t)));
    }

    @Test
    void postCreatePushesToFollowerInboxOnly() throws Exception {
        LoginUser b = loginAs(PHONE_B, "宝藏博主");
        LoginUser c = loginAs(PHONE_C, "路人");
        follow(tokenA, b.userId());
        long before = System.currentTimeMillis();
        Long postId = createPostViaApi(b.token(), "粉丝特供帖");
        long after = System.currentTimeMillis();

        // 关注者收件箱有该帖，score 位账=毫秒<<12|postId低12位
        Double score = redisCache.zsets().score(
                keyBuilder.build(KeyManage.USER_FEED, userIdA), String.valueOf(postId));
        assertTrue(score != null, "关注者收件箱应有该帖");
        long s = score.longValue();
        assertEquals(postId & 0xFFF, s & 0xFFF, "低 12 位=雪花序列位");
        assertTrue((s >> 12) >= before - 10 && (s >> 12) <= after + 10, "时间位=发帖毫秒");
        // 非关注者无收件箱（未推送）；作者本人也不推（关注流=我关注的人的内容）
        assertFalse(redisCache.hasKey(keyBuilder.build(KeyManage.USER_FEED, c.userId())));
        assertFalse(redisCache.hasKey(keyBuilder.build(KeyManage.USER_FEED, b.userId())));
    }

    @Test
    void scrollPaginationNoRepeatNoMiss() throws Exception {
        LoginUser b = loginAs(PHONE_B, "连载博主");
        follow(tokenA, b.userId());
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(createPostViaApi(b.token(), "连载第" + i + "篇"));
        }

        FeedPage page1 = feedPage(tokenA, null, 2);
        assertEquals(List.of(ids.get(4), ids.get(3)), page1.ids(), "第一页最新两条倒序");
        FeedPage page2 = feedPage(tokenA, page1.nextCursor(), 2);
        assertEquals(List.of(ids.get(2), ids.get(1)), page2.ids(), "第二页接着翻");
        FeedPage page3 = feedPage(tokenA, page2.nextCursor(), 2);
        assertEquals(List.of(ids.get(0)), page3.ids(), "第三页收尾");
        assertNull(page3.nextCursor(), "候选耗尽 nextCursor=null，客户端见 null 即停（null 语义=从头开始而非空页）");
    }

    @Test
    void bigVAuthorNotPushedButPulledAndMerged() throws Exception {
        LoginUser b = loginAs(PHONE_B, "普通博主");
        LoginUser v = loginAs(PHONE_V, "百万大V");
        setFans(v.userId(), 1000);  // 大 V 阈值（默认 1000）
        follow(tokenA, b.userId());
        follow(tokenA, v.userId());

        Long vPost = createPostViaApi(v.token(), "大V长文");
        // 拉路径 score 时间位=DB 秒级精度：与后发的推路径帖拉开 >1s，保证归并顺序稳定
        Thread.sleep(1100);
        Long bPost = createPostViaApi(b.token(), "普通人日常");

        // 大 V 不推：A 收件箱只有 B 的帖
        assertNull(redisCache.zsets().score(
                keyBuilder.build(KeyManage.USER_FEED, userIdA), String.valueOf(vPost)));
        assertTrue(redisCache.zsets().score(
                keyBuilder.build(KeyManage.USER_FEED, userIdA), String.valueOf(bPost)) != null);

        // 读端归并：两帖都可见，B 帖（更新）在前，V 帖走拉模式补上
        FeedPage page = feedPage(tokenA, null, 10);
        assertEquals(List.of(bPost, vPost), page.ids(), "推(新)在前、拉(旧)在后归并");
        assertNull(page.nextCursor());
    }

    @Test
    void unfollowTakesEffectOnReadWithoutInboxCleanup() throws Exception {
        LoginUser b = loginAs(PHONE_B, "即将取关的博主");
        follow(tokenA, b.userId());
        Long postId = createPostViaApi(b.token(), "取关前的帖");
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/follow/{id}", b.userId())
                        .header("Authorization", tokenA))
                .andExpect(jsonPath("$.code").value(0));

        // 收件箱残留（推送时刻快照，不逐粉丝清理）
        assertTrue(redisCache.zsets().score(
                keyBuilder.build(KeyManage.USER_FEED, userIdA), String.valueOf(postId)) != null);
        // 读端按当前关注集合过滤：feed 为空
        FeedPage page = feedPage(tokenA, null, 10);
        assertTrue(page.ids().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void sameSecondPostsPagedByScoreBitsWithoutMiss() throws Exception {
        LoginUser v = loginAs(PHONE_V, "量产大V");
        setFans(v.userId(), 1000);
        follow(tokenA, v.userId());
        // 直插同 create_time 四帖，id 低 12 位手动错开（0x111/0x222/0x333/0x444）——
        // 同秒并列全靠位账区分，score 序=id 低 12 位倒序（与插入顺序相反）
        LocalDateTime sameSecond = LocalDateTime.now().withNano(0);
        long base = 9100000000000000000L;
        long[] seqs = {0x333, 0x111, 0x444, 0x222};  // 乱序插入
        for (long seq : seqs) {
            insertPostDirectly(base + seq, v.userId(), sameSecond);
        }

        FeedPage page1 = feedPage(tokenA, null, 2);
        assertEquals(List.of(base + 0x444, base + 0x333), page1.ids(), "同秒按低 12 位倒序");
        FeedPage page2 = feedPage(tokenA, page1.nextCursor(), 2);
        assertEquals(List.of(base + 0x222, base + 0x111), page2.ids(), "翻页不重不漏");
        assertTrue(page2.nextCursor() != null, "本页取满 → 游标继续下探");
        FeedPage page3 = feedPage(tokenA, page2.nextCursor(), 2);
        assertTrue(page3.ids().isEmpty(), "同秒 4 帖耗尽，空页到底");
        assertNull(page3.nextCursor());
    }

    @Test
    void authRequiredAndAuditFilteredPostNotBlockingCursor() throws Exception {
        mockMvc.perform(get("/api/feed"))
                .andExpect(jsonPath("$.code").value(40002));

        LoginUser b = loginAs(PHONE_B, "被审核博主");
        follow(tokenA, b.userId());
        Long p1 = createPostViaApi(b.token(), "老帖");
        Thread.sleep(1050);
        Long p2 = createPostViaApi(b.token(), "被驳回帖");
        Thread.sleep(1050);
        Long p3 = createPostViaApi(b.token(), "新帖");
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, p2).set(Post::getAuditStatus, 0));

        // 第一页 size=2：候选 [p3,p2]，p2 被回填过滤但 cursor 越过它
        FeedPage page1 = feedPage(tokenA, null, 2);
        assertEquals(List.of(p3), page1.ids());
        assertTrue(page1.nextCursor() != null, "cursor=被过滤帖的 score，不阻塞翻页");
        // 第二页：p1 正常可达
        FeedPage page2 = feedPage(tokenA, page1.nextCursor(), 2);
        assertEquals(List.of(p1), page2.ids());
        assertNull(page2.nextCursor());
    }

    // ===== 工具 =====

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

    private void follow(String token, Long targetId) throws Exception {
        mockMvc.perform(post("/api/follow/{id}", targetId).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));
    }

    private Long createPostViaApi(String token, String title) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"content\":\"" + title + "-内容\"}";
        String resp = mockMvc.perform(post("/api/post").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(com.alibaba.fastjson2.JSON.parseObject(resp).getString("data"));
    }

    private void insertPostDirectly(Long id, Long userId, LocalDateTime createTime) {
        Post post = new Post();
        post.setId(id);
        post.setUserId(userId);
        post.setTitle("直插-" + id);
        post.setImages("");
        post.setContent("直插内容-" + id);
        post.setLiked(0);
        post.setComments(0);
        post.setViewed(0);
        post.setAuditStatus(1);
        post.setCreateTime(createTime);
        postMapper.insert(post);
    }

    private void setFans(Long userId, int fans) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId).set(User::getFans, fans));
    }

    private FeedPage feedPage(String token, Long lastScore, int size) throws Exception {
        StringBuilder url = new StringBuilder("/api/feed?size=" + size);
        if (lastScore != null) {
            url.append("&lastScore=").append(lastScore);
        }
        String resp = mockMvc.perform(get(url.toString()).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        com.alibaba.fastjson2.JSONObject data = com.alibaba.fastjson2.JSON.parseObject(resp)
                .getJSONObject("data");
        List<Long> ids = data.getJSONArray("records").stream()
                .map(o -> ((com.alibaba.fastjson2.JSONObject) o).getLong("id"))
                .toList();
        return new FeedPage(ids, data.getLong("nextCursor"));
    }

    private record LoginUser(String token, Long userId) {
    }

    private record FeedPage(List<Long> ids, Long nextCursor) {
    }
}
