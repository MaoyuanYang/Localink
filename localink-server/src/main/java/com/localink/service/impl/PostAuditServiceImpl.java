package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.event.PostDeletedEvent;
import com.localink.framework.dfa.SensitiveWordDFA;
import com.localink.mapper.PostMapper;
import com.localink.service.PostAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 帖子异步审核实现（M6-F）：隐性词库复审命中 → 驳回。驳回在事务内置 audit=2 并发布
 * PostDeletedEvent——消费端无外层事务，本方法自带 @Transactional 才能让
 * AFTER_COMMIT 事件监听器（ES 删文档）在提交后触发；驳回语义=内容撤下，
 * 与删帖共享 M6-D 的 DELETE 同步链路（设计时预留兑现）。
 * Feed/热榜/点赞/搜索的 audit=1 读端过滤自 M6-A 起已全线立好，驳回即刻生效。
 */
@Service
@RequiredArgsConstructor
public class PostAuditServiceImpl implements PostAuditService {

    private final PostMapper postMapper;
    private final SensitiveWordDFA auditRiskWordDfa;
    private final ApplicationEventPublisher eventPublisher;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @Override
    @Transactional
    public boolean rejectIfRisky(Long postId) {
        Post post = postMapper.selectById(postId);
        // 帖已删（audit 过滤前先判空）/已驳回（重复消息幂等）/未过审态不重复处理
        if (post == null || post.getAuditStatus() == null || post.getAuditStatus() != 1) {
            return false;
        }
        // title/content 分开扫描：拼接会让标题尾字+正文首字拼出敏感词跨字段误杀
        //（DFA 跳干扰字符，分隔符救不了，只能不拼接）
        boolean risky = auditRiskWordDfa.contains(nullToEmpty(post.getTitle()))
                || auditRiskWordDfa.contains(nullToEmpty(post.getContent()));
        if (!risky) {
            return false;
        }
        doReject(postId);
        return true;
    }

    /**
     * 重试耗尽兜底（B-7）：fail-closed 强制驳回——宁可错杀不可漏审；
     * 存储同故障时由 Recoverer 层 error 告警人工介入。
     */
    @Override
    @Transactional
    public void forceReject(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getAuditStatus() == null || post.getAuditStatus() != 1) {
            return;
        }
        doReject(postId);
    }

    private void doReject(Long postId) {
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId)
                .eq(Post::getAuditStatus, 1)
                .set(Post::getAuditStatus, 2));
        // 对齐删帖链路的派生视图清理（B-10）：驳回帖不得残留点赞榜/热榜僵尸 member 与孤儿 UV key
        redisCache.zsets().remove(keyBuilder.build(KeyManage.POST_LIKE_TOP), String.valueOf(postId));
        redisCache.zsets().remove(keyBuilder.build(KeyManage.POST_HOT_TOP), String.valueOf(postId));
        redisCache.delete(keyBuilder.build(KeyManage.POST_UV, postId));
        eventPublisher.publishEvent(new PostDeletedEvent(postId));
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
