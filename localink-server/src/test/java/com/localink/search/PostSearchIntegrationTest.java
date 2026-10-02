package com.localink.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.entity.PostComment;
import com.localink.entity.PostLike;
import com.localink.entity.Shop;
import com.localink.entity.User;
import com.localink.mapper.FollowMapper;
import com.localink.mapper.PostCommentMapper;
import com.localink.mapper.PostLikeMapper;
import com.localink.mapper.PostMapper;
import com.localink.mapper.ShopMapper;
import com.localink.mapper.UserMapper;
import com.localink.mq.MessageProducer;
import com.localink.mq.MqTopics;
import com.localink.mq.PostSearchMessage;
import com.localink.mq.PostSyncEvent;
import com.localink.service.PostSearchService;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * M6-D 搜索验证：Kafka 同步链路（发帖 UPSERT/删帖 DELETE，AFTER_COMMIT+key=postId）、
 * 检索+高亮+XSS 转义、post_filter 侧栏全集 vs hits 过滤、search_after 深分页翻页、
 * upsert 幂等（后到覆盖最新态）、rebuildAll 只灌过审帖。
 * 前置：ES/MySQL/Redis/Kafka 四容器在跑。
 */
// @SpyBean 使本类独占一个 ApplicationContext（Mockito 定制器改变缓存键，各占 2 个分片连接池）；
// 跑完即关闭，避免全量运行时叠加把本机 MySQL max_connections 挤爆（同 FeedInboxTrim 先例）
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
class PostSearchIntegrationTest {

    private static final String PHONE_AUTHOR = "13900139401";

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
    private ShopMapper shopMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private ElasticsearchClient client;

    @Autowired
    private PostSearchService postSearchService;

    @SpyBean
    private MessageProducer messageProducer;

    private final List<Long> userIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();
    private String token;
    private Long userId;

    @BeforeEach
    void setUp() throws Exception {
        // 社区表与 ES 索引均为测试独占：每例前清空（消费端查 DB 查不到→跳过，残留消息无害）
        commentMapper.delete(null);
        postLikeMapper.delete(null);
        followMapper.delete(null);
        postMapper.delete(null);
        deleteIndexQuietly();
        LoginUser author = loginAs(PHONE_AUTHOR, "搜索作者");
        token = author.token();
        userId = author.userId();
    }

    @AfterEach
    void cleanup() {
        commentMapper.delete(null);
        postLikeMapper.delete(null);
        followMapper.delete(null);
        postMapper.delete(null);
        deleteIndexQuietly();
        userIds.forEach(id -> {
            redisCache.delete(keyBuilder.build(KeyManage.USER_FEED, id));
            redisCache.delete(keyBuilder.build(KeyManage.USER_FOLLOWEE, id));
        });
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE_AUTHOR));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE_AUTHOR));
        issuedTokens.forEach(t -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, t)));
    }

    @Test
    void createPostSyncsToEsViaKafkaWithPostIdKey() throws Exception {
        Long postId = createPostViaApi("牛肉面探店", "汤头浓郁的牛肉面");
        // 事务提交后发消息，key=postId（同帖同分区 FIFO）；载荷只带 postId+事件类型
        verify(messageProducer).sendAsync(ArgumentMatchers.eq(MqTopics.POST_SEARCH_SYNC),
                ArgumentMatchers.eq(String.valueOf(postId)),
                ArgumentMatchers.argThat(m -> m instanceof PostSearchMessage msg
                        && postId.equals(msg.postId()) && msg.event() == PostSyncEvent.UPSERT));
        assertTrue(awaitDoc(postId, true), "消费端应把帖子写进 ES");
        PostDocument doc = client.get(g -> g.index("post").id(String.valueOf(postId)), PostDocument.class).source();
        assertNotNull(doc);
        assertEquals("牛肉面探店", doc.getTitle());
        assertEquals("搜索作者", doc.getNickName(), "消费端查 DB 回填作者昵称");
    }

    @Test
    void deletePostRemovesEsDoc() throws Exception {
        Long postId = createPostViaApi("火锅必吃榜", "麻辣锅底");
        assertTrue(awaitDoc(postId, true));
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/post/{id}", postId)
                        .header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));
        verify(messageProducer).sendAsync(ArgumentMatchers.eq(MqTopics.POST_SEARCH_SYNC),
                ArgumentMatchers.eq(String.valueOf(postId)),
                ArgumentMatchers.argThat(m -> m instanceof PostSearchMessage msg
                        && postId.equals(msg.postId()) && msg.event() == PostSyncEvent.DELETE));
        assertTrue(awaitDoc(postId, false), "删帖后 ES 文档应被移除");
    }

    @Test
    void keywordSearchRanksHighlightsAndEscapesHtml() throws Exception {
        Long titleHit = insertPostDirectly("火锅探店记", "锅气十足", null, nowMinusSeconds(30));
        Long contentHit = insertPostDirectly("烧烤小聚", "氛围和火锅一样热闹", null, nowMinusSeconds(20));
        Long xss = insertPostDirectly("火锅<script>alert(1)</script>", "正文", null, nowMinusSeconds(10));
        postSearchService.rebuildAll();

        String resp = searchViaApi("火锅", null, "relevance", null, 10);
        List<Long> ids = recordIds(resp);
        assertEquals(3, ids.size());
        assertEquals(titleHit, ids.get(0), "标题命中加权 2 倍应排最前");
        String titleHighlight = highlightOf(resp, titleHit, "titleHighlight");
        assertTrue(titleHighlight.contains("<em>火锅</em>"), "标题高亮: " + titleHighlight);
        assertNotNull(highlightOf(resp, contentHit, "contentHighlight"), "正文命中返回内容片段");
        // XSS：入索引前 HTML 转义，高亮片段中 script 已失去标签语义
        String xssTitle = fieldOf(resp, xss, "title");
        assertTrue(xssTitle.contains("&lt;script&gt;"), "入索引前应转义: " + xssTitle);
    }

    @Test
    void postFilterKeepsFacetFullSetWhileHitsFiltered() throws Exception {
        Long shop1 = insertShopDirectly("一号火锅店");
        Long shop2 = insertShopDirectly("二号火锅店");
        insertPostDirectly("火锅日记", "内容一", shop1, nowMinusSeconds(30));
        insertPostDirectly("火锅周记", "内容二", shop1, nowMinusSeconds(20));
        insertPostDirectly("火锅月记", "内容三", shop2, nowMinusSeconds(10));
        postSearchService.rebuildAll();

        String resp = searchViaApi("火锅", shop1, "relevance", null, 10);
        List<Long> ids = recordIds(resp);
        assertEquals(2, ids.size(), "hits 只含 shop1 的帖（post_filter）");
        var facets = com.alibaba.fastjson2.JSON.parseObject(resp).getJSONObject("data")
                .getJSONArray("shopFacets");
        assertEquals(2, facets.size(), "侧栏聚合保搜索全集（不受 post_filter 影响）");
        var facetMap = new java.util.HashMap<Long, Long>();
        var nameMap = new java.util.HashMap<Long, String>();
        for (Object o : facets) {
            var f = (com.alibaba.fastjson2.JSONObject) o;
            facetMap.put(f.getLong("shopId"), f.getLong("count"));
            nameMap.put(f.getLong("shopId"), f.getString("shopName"));
        }
        assertEquals(2L, facetMap.get(shop1));
        assertEquals(1L, facetMap.get(shop2));
        assertEquals("二号火锅店", nameMap.get(shop2), "店名由 DB 回填");
    }

    @Test
    void searchAfterPaginationByTimeNoRepeatNoMiss() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(insertPostDirectly("火锅连载" + i, "第" + i + "篇内容", null,
                    nowMinusSeconds(60 - i * 10)));
        }
        postSearchService.rebuildAll();

        String p1 = searchViaApi("火锅", null, "time", null, 2);
        assertEquals(List.of(ids.get(4), ids.get(3)), recordIds(p1));
        String cursor1 = nextCursor(p1);
        assertNotNull(cursor1);
        String p2 = searchViaApi("火锅", null, "time", cursor1, 2);
        assertEquals(List.of(ids.get(2), ids.get(1)), recordIds(p2), "游标续翻不重不漏");
        String p3 = searchViaApi("火锅", null, "time", nextCursor(p2), 2);
        assertEquals(List.of(ids.get(0)), recordIds(p3));
        assertNull(nextCursor(p3), "不足一页=到底");
    }

    @Test
    void duplicateUpsertOverwritesWithLatestState() throws Exception {
        Long postId = createPostViaApi("改名前的标题", "内容");
        assertTrue(awaitDoc(postId, true));
        // 直改事实源后手动再发一条 UPSERT（等价于重投/乱序后到）——upsert 后到覆盖=幂等
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId).set(Post::getTitle, "改名后的标题"));
        messageProducer.sendSync(MqTopics.POST_SEARCH_SYNC, String.valueOf(postId),
                new PostSearchMessage(postId, PostSyncEvent.UPSERT));
        long deadline = System.currentTimeMillis() + 20_000;
        String title = null;
        while (System.currentTimeMillis() < deadline) {
            PostDocument doc = client.get(g -> g.index("post").id(String.valueOf(postId)), PostDocument.class).source();
            if (doc != null && "改名后的标题".equals(doc.getTitle())) {
                title = doc.getTitle();
                break;
            }
            Thread.sleep(150);
        }
        assertEquals("改名后的标题", title, "重复 UPSERT 应以事实源最新态覆盖且不产生重复文档");
    }

    @Test
    void likeResyncsLikedCountToEsDoc() throws Exception {
        // T2 修复 F-8：赞数是 ES 索引时快照，点赞后应通过 UPSERT 重发刷新（发帖 1 次 + 点赞 1 次）
        Long postId = createPostViaApi("点赞同步验证帖", "赞数快照刷新");
        assertTrue(awaitDoc(postId, true));
        mockMvc.perform(post("/api/post/{id}/like", postId).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));
        verify(messageProducer, org.mockito.Mockito.times(2)).sendAsync(
                ArgumentMatchers.eq(MqTopics.POST_SEARCH_SYNC),
                ArgumentMatchers.eq(String.valueOf(postId)),
                ArgumentMatchers.argThat(m -> m instanceof PostSearchMessage msg
                        && postId.equals(msg.postId()) && msg.event() == PostSyncEvent.UPSERT));
        long deadline = System.currentTimeMillis() + 20_000;
        Integer liked = null;
        while (System.currentTimeMillis() < deadline) {
            PostDocument doc = client.get(g -> g.index("post").id(String.valueOf(postId)), PostDocument.class).source();
            if (doc != null && doc.getLiked() != null && doc.getLiked() >= 1) {
                liked = doc.getLiked();
                break;
            }
            Thread.sleep(150);
        }
        assertNotNull(liked, "点赞后 ES 文档 liked 应被 UPSERT 刷新为最新赞数");
    }

    @Test
    void rebuildAllIndexesOnlyAuditedPosts() throws Exception {
        insertPostDirectly("过审火锅帖一", "内容", null, nowMinusSeconds(30));
        insertPostDirectly("过审火锅帖二", "内容", null, nowMinusSeconds(20));
        Long rejected = insertPostDirectly("待审火锅帖", "内容", null, nowMinusSeconds(10));
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, rejected).set(Post::getAuditStatus, 0));

        long count = postSearchService.rebuildAll();
        assertEquals(2, count, "只灌 audit=1");
        String resp = searchViaApi("火锅", null, "relevance", null, 10);
        assertEquals(2, recordIds(resp).size());
        assertFalse(recordIds(resp).contains(rejected), "未过审帖不进索引");
    }

    // ===== 工具 =====

    private LoginUser loginAs(String phone, String nickName) {
        smsService.sendCode(phone);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, phone));
        String t = userService.login(phone, code);
        issuedTokens.add(t);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId()).set(User::getNickName, nickName));
        userIds.add(user.getId());
        return new LoginUser(t, user.getId());
    }

    private Long createPostViaApi(String title, String content) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"content\":\"" + content + "\"}";
        String resp = mockMvc.perform(post("/api/post").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(com.alibaba.fastjson2.JSON.parseObject(resp).getString("data"));
    }

    private Long insertPostDirectly(String title, String content, Long shopId, LocalDateTime createTime) {
        Post post = new Post();
        post.setUserId(userId);
        post.setShopId(shopId);
        post.setTitle(title);
        post.setImages("");
        post.setContent(content);
        post.setLiked(0);
        post.setComments(0);
        post.setViewed(0);
        post.setAuditStatus(1);
        post.setCreateTime(createTime);
        postMapper.insert(post);
        return post.getId();
    }

    private Long insertShopDirectly(String name) {
        Shop shop = new Shop();
        shop.setName(name);
        shop.setTypeId(1L);
        shop.setImages("");
        shop.setAddress("测试地址");
        shop.setLongitude(0.0);
        shop.setLatitude(0.0);
        shopMapper.insert(shop);
        return shop.getId();
    }

    private String searchViaApi(String keyword, Long shopId, String sort, String searchAfter, int size)
            throws Exception {
        // queryParam 由 MockMvc 负责编码——手工 URLEncoder 预编码中文会被 URI 模板二次处理成乱码
        var request = get("/api/search/post")
                .queryParam("keyword", keyword)
                .queryParam("sort", sort)
                .queryParam("size", String.valueOf(size));
        if (shopId != null) {
            request.queryParam("shopId", String.valueOf(shopId));
        }
        if (searchAfter != null) {
            request.queryParam("searchAfter", searchAfter);
        }
        return mockMvc.perform(request)
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
    }

    private List<Long> recordIds(String resp) {
        return com.alibaba.fastjson2.JSON.parseObject(resp).getJSONObject("data")
                .getJSONArray("records").stream()
                .map(o -> ((com.alibaba.fastjson2.JSONObject) o).getLong("id"))
                .toList();
    }

    private String nextCursor(String resp) {
        return com.alibaba.fastjson2.JSON.parseObject(resp).getJSONObject("data").getString("nextSearchAfter");
    }

    private String fieldOf(String resp, Long id, String field) {
        return com.alibaba.fastjson2.JSON.parseObject(resp).getJSONObject("data")
                .getJSONArray("records").stream()
                .map(o -> (com.alibaba.fastjson2.JSONObject) o)
                .filter(o -> id.equals(o.getLong("id"))).findFirst().orElseThrow()
                .getString(field);
    }

    private String highlightOf(String resp, Long id, String field) {
        return fieldOf(resp, id, field);
    }

    private boolean awaitDoc(Long postId, boolean expectFound) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (esGet(postId) == expectFound) {
                postSearchService.refresh();
                return true;
            }
            Thread.sleep(150);
        }
        return false;
    }

    private boolean esGet(Long postId) {
        try {
            return client.get(g -> g.index("post").id(String.valueOf(postId)), PostDocument.class).found();
        } catch (ElasticsearchException e) {
            // 404=文档不存在；5xx（索引刚建主分片 RECOVERING 的 no_shard_available）=还没好继续轮询
            if (e.status() == 404 || e.status() >= 500) {
                return false;
            }
            throw e;
        } catch (IOException e) {
            // 低层 RestClient 同样以 503 抛 ResponseException（IOException 子类）——视为未就绪
            return false;
        }
    }

    private void deleteIndexQuietly() {
        try {
            client.indices().delete(d -> d.index("post"));
        } catch (ElasticsearchException | IOException e) {
            // 索引不存在则忽略
        }
    }

    private LocalDateTime nowMinusSeconds(int seconds) {
        return LocalDateTime.now().minusSeconds(seconds).withNano(0);
    }

    private record LoginUser(String token, Long userId) {
    }
}
