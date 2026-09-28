package com.localink.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.entity.User;
import com.localink.mapper.PostMapper;
import com.localink.mapper.UserMapper;
import com.localink.mq.MessageProducer;
import com.localink.mq.MqTopics;
import com.localink.mq.PostAuditMessage;
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
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * M6-F 两级审核验证：同步初筛（显性词拒发帖不落库/评论同级拦截）、先发后审（正常帖立即可见
 * 且投递复审消息）、异步驳回收回（隐性词命中→audit=2+复用 PostDeletedEvent 删 ES+读端过滤
 * 即刻生效）、重复复审幂等。前置：ES/MySQL/Redis/Kafka 四容器在跑。
 */
// @SpyBean 独立上下文（连接池翻倍），跑完即关防挤爆本机 MySQL（M6-D/E 同款）
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
class PostAuditIntegrationTest {

    private static final String PHONE = "13900139601";

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

    @Autowired
    private ElasticsearchClient client;

    @SpyBean
    private MessageProducer messageProducer;

    private final List<String> issuedTokens = new ArrayList<>();
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        postMapper.delete(null);
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        token = userService.login(PHONE, code);
        issuedTokens.add(token);
    }

    @AfterEach
    void cleanup() {
        postMapper.delete(null);
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        issuedTokens.forEach(t -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, t)));
    }

    @Test
    void syncScreenRejectsExplicitWordWithoutPersistence() throws Exception {
        mockMvc.perform(post("/api/post").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"路边见闻\",\"content\":\"有人拉我去赌博网站\"}"))
                .andExpect(jsonPath("$.code").value(30001))
                .andExpect(jsonPath("$.message").value("内容包含敏感词，已被驳回"));
        assertEquals(0, postMapper.selectCount(null), "显性词命中帖不落库");
    }

    @Test
    void cleanPostVisibleImmediatelyAndAuditMessageSent() throws Exception {
        String resp = mockMvc.perform(post("/api/post").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"西湖晨跑\",\"content\":\"早起绕湖一圈，神清气爽\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        Long postId = Long.valueOf(com.alibaba.fastjson2.JSON.parseObject(resp).getString("data"));

        // 先发后审：audit=1 立即可见
        mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("西湖晨跑"));
        // 复审消息已按 key=postId 投递（事务提交后）
        verify(messageProducer).sendAsync(ArgumentMatchers.eq(MqTopics.POST_AUDIT),
                ArgumentMatchers.eq(String.valueOf(postId)),
                ArgumentMatchers.argThat(m -> m instanceof PostAuditMessage msg
                        && postId.equals(msg.postId())));
    }

    @Test
    void asyncRecheckRejectsRiskWordAndRevokesEverywhere() throws Exception {
        String resp = mockMvc.perform(post("/api/post").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"理财分享\",\"content\":\"跟我做，稳赚不赔\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        Long postId = Long.valueOf(com.alibaba.fastjson2.JSON.parseObject(resp).getString("data"));

        // 先发后审：创建成功即初筛放行（audit=1 落库，中间态可见性由干净帖用例覆盖——
        // 驳回消费是秒级热链路，断言"创建后仍可见"存在竞速，本用例直取终态）
        // 终态：异步复审驳回——audit=2，读端过滤即刻生效，ES 文档随 PostDeletedEvent 链路删除
        assertTrue(awaitAudit(postId, 2), "隐性词复审应驳回");
        mockMvc.perform(get("/api/post/{id}", postId).header("Authorization", token))
                .andExpect(jsonPath("$.code").value(40004));
        assertTrue(awaitEsDoc(postId, false), "驳回复用删帖事件链路删除 ES 文档");

        // 阶段三：重复复审幂等（手动重投复审消息，audit 仍为 2 不报错）
        messageProducer.sendSync(MqTopics.POST_AUDIT, String.valueOf(postId), new PostAuditMessage(postId));
        Thread.sleep(1500);
        assertEquals(2, postMapper.selectById(postId).getAuditStatus().intValue());
    }

    @Test
    void commentScreenRejectsExplicitWord() throws Exception {
        String resp = mockMvc.perform(post("/api/post").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"日常\",\"content\":\"随便聊聊\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        Long postId = Long.valueOf(com.alibaba.fastjson2.JSON.parseObject(resp).getString("data"));

        mockMvc.perform(post("/api/post/comment").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":" + postId + ",\"content\":\"可以代开发票吗\"}"))
                .andExpect(jsonPath("$.code").value(30001));
        assertEquals(0, postMapper.selectById(postId).getComments().intValue(), "被拒评论不计数");
    }

    // ===== 工具 =====

    private boolean awaitAudit(Long postId, int expectedStatus) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Post post = postMapper.selectById(postId);
            if (post != null && post.getAuditStatus() != null
                    && post.getAuditStatus() == expectedStatus) {
                return true;
            }
            Thread.sleep(150);
        }
        return false;
    }

    private boolean awaitEsDoc(Long postId, boolean expectFound) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (esGet(postId) == expectFound) {
                return true;
            }
            Thread.sleep(150);
        }
        return false;
    }

    private boolean esGet(Long postId) {
        try {
            return client.get(g -> g.index("post").id(String.valueOf(postId)),
                    com.localink.search.PostDocument.class).found();
        } catch (ElasticsearchException e) {
            if (e.status() == 404 || e.status() >= 500) {
                return false;
            }
            throw e;
        } catch (IOException e) {
            return false;
        }
    }
}
