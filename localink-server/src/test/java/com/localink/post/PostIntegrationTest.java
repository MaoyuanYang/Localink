package com.localink.post;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.entity.PostComment;
import com.localink.entity.User;
import com.localink.mapper.PostCommentMapper;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6-A 内容基础验证：图片上传（存储+静态访问+类型校验）、帖子（发/删/详情/分页+审核过滤）、
 * 两级评论（楼中楼规则+replyNickName+计数增减）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostIntegrationTest {

    private static final String PHONE = "13900139018";
    private static final byte[] PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52};

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
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    private final List<Long> createdPostIds = new ArrayList<>();
    private final List<String> issuedTokens = new ArrayList<>();
    private String token;
    private Long userId;

    @BeforeEach
    void login() throws Exception {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        token = userService.login(PHONE, code);
        issuedTokens.add(token);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        userId = user.getId();
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                .eq(User::getId, userId).set(User::getNickName, "探店达人"));
        // 社区表为测试独占：每例前清空，杜绝历史脏数据破坏绝对值断言
        commentMapper.delete(null);
        postMapper.delete(null);
    }

    @AfterEach
    void cleanup() {
        createdPostIds.forEach(id -> {
            commentMapper.delete(new LambdaQueryWrapper<PostComment>().eq(PostComment::getPostId, id));
            postMapper.deleteById(id);
        });
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        issuedTokens.forEach(t -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, t)));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
    }

    @Test
    void imageUploadStoresFileAndServesItStatically() throws Exception {
        String url = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/upload/image")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String imageUrl = com.alibaba.fastjson2.JSON.parseObject(url).getString("data");
        assertTrue(imageUrl.startsWith("/upload/"), "应返回相对 URL: " + imageUrl);

        mockMvc.perform(get(imageUrl)).andExpect(status().isOk());

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/upload/image")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                        .header("Authorization", token))
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void createPostWithImageAndDetailIncrementsViewed() throws Exception {
        String body = "{\"title\":\"老字号牛肉面\",\"images\":\"/upload/202609/a.png,/upload/202609/b.png\","
                + "\"content\":\"汤头浓郁，值得二刷\",\"shopId\":1}";
        String resp = mockMvc.perform(post("/api/post").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        Long postId = Long.valueOf(com.alibaba.fastjson2.JSON.parseObject(resp).getString("data"));
        assertNotNull(postId);
        createdPostIds.add(postId);

        mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.nickName").value("探店达人"))
                .andExpect(jsonPath("$.data.viewed").value(1))
                .andExpect(jsonPath("$.data.images.length()").value(2));

        Post post = postMapper.selectById(postId);
        postMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId).set(Post::getAuditStatus, 0));
        mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(40004));
        postMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId).set(Post::getAuditStatus, 1));
        assertEquals(1, post.getAuditStatus().intValue(), "M6-A 发帖直接过审");
    }

    @Test
    void pageReturnsTotalRecordsDescendingAndFiltersUnaudited() throws Exception {
        Long p1 = createPostDirectly("第一篇");
        Long p2 = createPostDirectly("第二篇");
        Long p3 = createPostDirectly("第三篇");
        // datetime 秒级精度：手工错开 create_time 保证倒序断言稳定
        staggerCreateTime(p1, java.time.LocalDateTime.now().minusSeconds(3));
        staggerCreateTime(p2, java.time.LocalDateTime.now().minusSeconds(2));
        staggerCreateTime(p3, java.time.LocalDateTime.now().minusSeconds(1));
        postMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                .eq(Post::getId, p1).set(Post::getAuditStatus, 0));

        mockMvc.perform(get("/api/post/page").header("Authorization", token)
                        .queryParam("page", "1").queryParam("size", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value(p3))
                .andExpect(jsonPath("$.data.records[1].id").value(p2));
    }

    @Test
    void deletePostCascadesComments() throws Exception {
        Long postId = createPostDirectly("待删帖");
        insertCommentDirectly(postId, "一级", 0L, 0L);
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/post/{id}", postId)
                        .header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));
        assertNull(postMapper.selectById(postId));
        assertEquals(0L, commentMapper.selectCount(new LambdaQueryWrapper<PostComment>()
                .eq(PostComment::getPostId, postId)));
    }

    @Test
    void twoLevelCommentsWithReplyNickNameAndCounting() throws Exception {
        Long postId = createPostDirectly("评论测试帖");
        Long topId = commentViaApi(postId, "一级评论", 0, 0);
        Long subId = commentViaApi(postId, "楼中楼回复", topId, 0);
        commentViaApi(postId, "回复楼中楼", topId, subId);

        mockMvc.perform(get("/api/post/{id}/comment/page", postId).header("Authorization", token)
                        .queryParam("page", "1").queryParam("size", "10"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(topId))
                .andExpect(jsonPath("$.data.records[0].children.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].children[1].replyNickName").value("探店达人"));

        assertEquals(3, postMapper.selectById(postId).getComments().intValue());
    }

    @Test
    void invalidParentHierarchyRejected() throws Exception {
        Long postId = createPostDirectly("层级校验帖");
        Long topId = commentViaApi(postId, "一级", 0, 0);
        Long subId = commentViaApi(postId, "楼中楼", topId, 0);

        String body = "{\"postId\":" + postId + ",\"content\":\"拿楼中楼当 parent\","
                + "\"parentId\":" + subId + ",\"replyId\":0}";
        mockMvc.perform(post("/api/post/comment").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(40001));

        String body2 = "{\"postId\":" + postId + ",\"content\":\"parent 不存在\",\"parentId\":999L}";
        mockMvc.perform(post("/api/post/comment").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(jsonPath("$.code").value(40001));
        assertEquals(2, postMapper.selectById(postId).getComments().intValue(), "被拒评论不计数");
    }

    @Test
    void deleteCommentRollsBackCount() throws Exception {
        Long postId = createPostDirectly("删评论帖");
        Long topId = commentViaApi(postId, "一级", 0, 0);
        commentViaApi(postId, "楼中楼1", topId, 0);
        commentViaApi(postId, "楼中楼2", topId, 0);
        assertEquals(3, postMapper.selectById(postId).getComments().intValue());

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/post/comment/{id}", topId)
                        .header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, postMapper.selectById(postId).getComments().intValue(), "删一级评论连带楼中楼全部回退");
    }

    private Long createPostDirectly(String title) {
        Post post = new Post();
        post.setUserId(userId);
        post.setShopId(1L);
        post.setTitle(title);
        post.setImages("");
        post.setContent("直插内容-" + System.nanoTime());
        post.setLiked(0);
        post.setComments(0);
        post.setViewed(0);
        post.setAuditStatus(1);
        postMapper.insert(post);
        createdPostIds.add(post.getId());
        return post.getId();
    }

    private void staggerCreateTime(Long postId, java.time.LocalDateTime time) {
        postMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId).set(Post::getCreateTime, time));
    }

    private void insertCommentDirectly(Long postId, String content, Long parentId, Long replyId) {
        PostComment comment = new PostComment();
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setReplyId(replyId);
        comment.setContent(content);
        comment.setLiked(0);
        comment.setAuditStatus(1);
        commentMapper.insert(comment);
    }

    private Long commentViaApi(Long postId, String content, long parentId, long replyId) throws Exception {
        String body = "{\"postId\":" + postId + ",\"content\":\"" + content
                + "\",\"parentId\":" + parentId + ",\"replyId\":" + replyId + "}";
        String resp = mockMvc.perform(post("/api/post/comment").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String id = com.alibaba.fastjson2.JSON.parseObject(resp).getString("data");
        return Long.valueOf(id);
    }
}
